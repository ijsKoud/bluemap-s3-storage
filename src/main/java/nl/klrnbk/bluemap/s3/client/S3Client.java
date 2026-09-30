package nl.klrnbk.bluemap.s3.client;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal S3 client on the JDK {@link HttpClient}. HTTP/1.1, real payload SHA-256 (no chunked or
 * trailer checksums, which Ceph RGW rejects), retries with full-jitter backoff, global rate and
 * concurrency limits. All methods block the calling thread; call them from upload or reader
 * threads, never from render threads.
 */
public final class S3Client implements AutoCloseable {

    public static final int MAX_DELETE_BATCH = 1000;

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);
    private static final Pattern CODE = Pattern.compile("<Code>([^<]{1,100})</Code>");
    private static final String EMPTY_SHA256 = SigV4Signer.sha256Hex(new byte[0]);

    private final S3ClientConfig config;
    private final SigV4Signer signer;
    private final HttpClient http;
    private final ExecutorService httpExecutor;
    private final RequestGate gate;
    private final S3Metrics metrics;
    private final URI endpoint;
    private final String authority;
    private final String pathPrefix;

    public S3Client(S3ClientConfig config, S3Metrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.signer = new SigV4Signer(config.accessKeyId(), config.secretAccessKey(), config.region(), "s3");
        this.gate = new RequestGate(config.maxInFlight(), config.maxRequestsPerSecond());

        this.endpoint = URI.create(config.endpointUrl());
        String hostPort = endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        if (config.pathStyle()) {
            this.authority = hostPort;
            this.pathPrefix = "/" + config.bucket();
        } else {
            this.authority = config.bucket() + "." + hostPort;
            this.pathPrefix = "";
        }

        AtomicInteger threadNumber = new AtomicInteger();
        this.httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "BlueMap-S3-Http-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(httpExecutor)
                .build();
    }

    public S3Metrics metrics() {
        return metrics;
    }

    public RequestGate gate() {
        return gate;
    }

    // ---- operations ----

    /** Returns the object body, or null if the key does not exist. */
    public byte[] get(String key) throws IOException {
        Response r = execute(S3Metrics.Op.GET, RequestGate.Kind.READ, "GET", key, Map.of(), null, Map.of());
        return r.status == 404 ? null : r.body;
    }

    public boolean head(String key) throws IOException {
        Response r = execute(S3Metrics.Op.HEAD, RequestGate.Kind.READ, "HEAD", key, Map.of(), null, Map.of());
        return r.status != 404;
    }

    public void put(String key, byte[] body, String contentType, String cacheControl) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", contentType);
        if (cacheControl != null && !cacheControl.isBlank()) headers.put("cache-control", cacheControl);
        execute(S3Metrics.Op.PUT, RequestGate.Kind.WRITE, "PUT", key, Map.of(), body, headers);
    }

    /** Deleting a missing key succeeds. */
    public void delete(String key) throws IOException {
        execute(S3Metrics.Op.DELETE, RequestGate.Kind.WRITE, "DELETE", key, Map.of(), null, Map.of());
    }

    public ListPage list(String prefix, String delimiter, String continuationToken, int maxKeys) throws IOException {
        Map<String, String> query = new HashMap<>();
        query.put("list-type", "2");
        query.put("prefix", prefix);
        if (delimiter != null) query.put("delimiter", delimiter);
        if (continuationToken != null) query.put("continuation-token", continuationToken);
        if (maxKeys > 0) query.put("max-keys", Integer.toString(maxKeys));
        Response r = execute(S3Metrics.Op.LIST, RequestGate.Kind.READ, "GET", "", query, null, Map.of());
        if (r.body == null) throw new S3Exception(r.status, null, "LIST " + shorten(prefix) + " -> HTTP " + r.status);
        try {
            return parseList(r.body);
        } catch (XMLStreamException e) {
            throw new S3Exception("Malformed ListObjectsV2 response", e);
        }
    }

    /** Visits every key under the prefix, following continuation tokens. */
    public void listAll(String prefix, java.util.function.Consumer<String> keys) throws IOException {
        String token = null;
        do {
            ListPage page = list(prefix, null, token, 0);
            page.keys().forEach(keys);
            token = page.nextToken();
        } while (token != null);
    }

    /**
     * Deletes up to {@link #MAX_DELETE_BATCH} keys in one request.
     *
     * @return the keys S3 reported as failed (empty on full success)
     */
    public List<String> deleteObjects(List<String> keys) throws IOException {
        if (keys.isEmpty()) return List.of();
        if (keys.size() > MAX_DELETE_BATCH) throw new IllegalArgumentException("Too many keys: " + keys.size());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Delete><Quiet>true</Quiet>");
        for (String key : keys) xml.append("<Object><Key>").append(xmlEscape(key)).append("</Key></Object>");
        xml.append("</Delete>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/xml");
        headers.put("content-md5", Base64.getEncoder().encodeToString(md5(body)));
        Response r = execute(S3Metrics.Op.DELETE_BATCH, RequestGate.Kind.WRITE, "POST", "",
                Map.of("delete", ""), body, headers);
        try {
            return parseDeleteErrors(r.body);
        } catch (XMLStreamException e) {
            throw new S3Exception("Malformed DeleteObjects response", e);
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
        httpExecutor.shutdownNow();
    }

    // ---- request execution ----

    private record Response(int status, byte[] body) {}

    private Response execute(S3Metrics.Op op, RequestGate.Kind kind, String method, String key,
                             Map<String, String> query, byte[] body, Map<String, String> extraHeaders) throws IOException {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            Response r = executeWithRetries(kind, method, key, query, body, extraHeaders);
            ok = true;
            return r;
        } finally {
            metrics.record(op, System.nanoTime() - start, ok);
        }
    }

    private Response executeWithRetries(RequestGate.Kind kind, String method, String key, Map<String, String> query,
                                        byte[] body, Map<String, String> extraHeaders) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = body == null ? EMPTY_SHA256 : SigV4Signer.sha256Hex(payload);
        String canonicalPath = pathPrefix + "/" + SigV4Signer.uriEncode(key, false);
        if (key.isEmpty() && !pathPrefix.isEmpty()) canonicalPath = pathPrefix; // "/bucket"
        if (key.isEmpty() && pathPrefix.isEmpty()) canonicalPath = "/";

        S3Exception last = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            if (attempt > 0) {
                metrics.retry();
                sleepBackoff(attempt);
            }
            try {
                gate.acquire(kind);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for a request slot");
            }
            try {
                HttpRequest request = buildRequest(method, canonicalPath, query, payload, payloadHash, extraHeaders, body != null);
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                byte[] responseBody = response.body();
                if (status >= 200 && status < 300) return new Response(status, responseBody);
                String code = errorCode(responseBody);
                if (status == 404 && !"NoSuchBucket".equals(code) && (method.equals("GET") || method.equals("HEAD") || method.equals("DELETE"))) {
                    return new Response(404, null);
                }
                S3Exception ex = new S3Exception(status, code, describe(method, key, status, code));
                if (RETRYABLE_STATUS.contains(status) || "SlowDown".equals(code)) {
                    last = ex;
                    continue;
                }
                throw ex; // 400, 403, 404 (bucket), ... never retried
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted during " + method);
            } catch (S3Exception e) {
                throw e;
            } catch (IOException e) {
                last = new S3Exception(method + " " + shorten(key) + " failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
            } finally {
                gate.release(kind);
            }
        }
        S3Exception exhausted = new S3Exception(last.status(), last.code(),
                last.getMessage() + " (gave up after " + (config.maxRetries() + 1) + " attempts)");
        exhausted.initCause(last.getCause());
        throw exhausted;
    }

    private HttpRequest buildRequest(String method, String canonicalPath, Map<String, String> query, byte[] payload,
                                     String payloadHash, Map<String, String> extraHeaders, boolean hasBody) {
        Instant now = Instant.now();
        Map<String, String> signed = new TreeMap<>();
        signed.put("host", authority);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", SigV4Signer.amzDate(now));
        signed.putAll(extraHeaders);
        String authorization = signer.authorization(method, canonicalPath, query, signed, payloadHash, now);

        String queryString = urlQuery(query);
        URI uri = URI.create(endpoint.getScheme() + "://" + authority + canonicalPath
                + (queryString.isEmpty() ? "" : "?" + queryString));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .method(method, hasBody ? HttpRequest.BodyPublishers.ofByteArray(payload) : HttpRequest.BodyPublishers.noBody());
        // The JDK client sets Host itself from the URI; it matches the signed value by construction.
        signed.forEach((k, v) -> {
            if (!k.equals("host")) b.header(k, v);
        });
        b.header("Authorization", authorization);
        return b.build();
    }

    /** Same encoding as the canonical query, but subresources without a value are sent as bare names. */
    private static String urlQuery(Map<String, String> query) {
        String canonical = SigV4Signer.canonicalQuery(query);
        return canonical.replaceAll("([?&]|^)([^=&]+)=(?=&|$)", "$1$2");
    }

    private void sleepBackoff(int attempt) throws InterruptedIOException {
        long base = config.backoffBase().toNanos();
        long cap = config.backoffCap().toNanos();
        long ceiling = Math.min(cap, base << Math.min(attempt - 1, 20));
        long nanos = ThreadLocalRandom.current().nextLong(ceiling + 1); // full jitter
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted during retry backoff");
        }
    }

    // ---- helpers ----

    private static String describe(String method, String key, int status, String code) {
        return method + " " + shorten(key) + " -> HTTP " + status + (code == null ? "" : " " + code);
    }

    private static String shorten(String key) {
        return key.length() <= 200 ? key : key.substring(0, 200) + "...";
    }

    static String errorCode(byte[] body) {
        if (body == null || body.length == 0) return null;
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8);
        Matcher m = CODE.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static XMLStreamReader xml(byte[] body) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(body));
    }

    static ListPage parseList(byte[] body) throws XMLStreamException {
        List<String> keys = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        String token = null;
        boolean truncated = false;
        Deque<String> path = new ArrayDeque<>();
        XMLStreamReader r = xml(body);
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    String parent = path.peek();
                    path.push(name);
                    if (name.equals("Key") && "Contents".equals(parent)) keys.add(r.getElementText());
                    else if (name.equals("Prefix") && "CommonPrefixes".equals(parent)) prefixes.add(r.getElementText());
                    else if (name.equals("NextContinuationToken")) token = r.getElementText();
                    else if (name.equals("IsTruncated")) truncated = Boolean.parseBoolean(r.getElementText());
                    if (r.getEventType() == XMLStreamConstants.END_ELEMENT) path.pop(); // getElementText consumed the end
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    path.pop();
                }
            }
        } finally {
            r.close();
        }
        return new ListPage(keys, prefixes, truncated ? token : null);
    }

    static List<String> parseDeleteErrors(byte[] body) throws XMLStreamException {
        if (body == null || body.length == 0) return List.of();
        List<String> failed = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        XMLStreamReader r = xml(body);
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    String parent = path.peek();
                    path.push(name);
                    if (name.equals("Key") && "Error".equals(parent)) failed.add(r.getElementText());
                    if (r.getEventType() == XMLStreamConstants.END_ELEMENT) path.pop();
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    path.pop();
                }
            }
        } finally {
            r.close();
        }
        return failed;
    }
}
