package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * In-process fake S3 (path style, single bucket) with fault injection, for contract and
 * throughput tests. It verifies the payload hash, Content-MD5 on DeleteObjects and the SigV4
 * signature (recomputed with the production signer from the headers the client declared).
 */
public final class FakeS3 implements AutoCloseable {

    public static final String BUCKET = "test-bucket";
    public static final String ACCESS_KEY = "AKIATESTKEY";
    public static final String SECRET_KEY = "test-secret-key";
    public static final String REGION = "hel1";

    public record StoredObject(byte[] data, String contentType, String cacheControl) {}

    /** Fault to inject for a matching request. */
    public record Fault(int status, String code, boolean reset, long hangMillis) {
        public static Fault status(int status, String code) { return new Fault(status, code, false, 0); }
        public static Fault connectionReset() { return new Fault(0, null, true, 0); }
        public static Fault hang(long millis) { return new Fault(0, null, false, millis); }
    }

    private final HttpServer server;
    public final ConcurrentHashMap<String, StoredObject> objects = new ConcurrentHashMap<>();
    public final ConcurrentLinkedQueue<Long> requestTimesNanos = new ConcurrentLinkedQueue<>();
    public final AtomicInteger requests = new AtomicInteger();
    public final AtomicInteger inFlight = new AtomicInteger();
    public final AtomicInteger maxInFlight = new AtomicInteger();
    public final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<>();
    public final AtomicInteger signatureFailures = new AtomicInteger();
    /** Two mutating requests for the same key were in flight at the same time (must stay 0). */
    public final AtomicInteger sameKeyOverlaps = new AtomicInteger();
    /** Mutating requests in the order they completed processing: "PUT key" / "DELETE key". */
    public final ConcurrentLinkedQueue<String> mutations = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeByKey = new ConcurrentHashMap<>();
    private final SigV4Signer verifier = new SigV4Signer(ACCESS_KEY, SECRET_KEY, REGION, "s3");

    public volatile long latencyMillis = 0;
    private final Queue<Fault> queuedFaults = new ConcurrentLinkedQueue<>();
    private volatile Predicate<String> faultFilter = r -> true;

    public FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public S3ClientConfig config(int maxRetries, int maxInFlight, int rps) {
        return new S3ClientConfig(endpoint(), REGION, BUCKET, ACCESS_KEY, SECRET_KEY, true,
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5), maxRetries, maxInFlight, rps,
                java.time.Duration.ofMillis(5), java.time.Duration.ofMillis(50));
    }

    /** The next {@code times} requests whose "METHOD path" matches the filter get this fault. */
    public void inject(Fault fault, int times, Predicate<String> filter) {
        faultFilter = filter;
        for (int i = 0; i < times; i++) queuedFaults.add(fault);
    }

    public void clearFaults() {
        queuedFaults.clear();
    }

    public void inject(Fault fault, int times) {
        inject(fault, times, r -> true);
    }

    public byte[] data(String key) {
        StoredObject o = objects.get(key);
        return o == null ? null : o.data();
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        requestTimesNanos.add(System.nanoTime());
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        String trackedKey = null;
        String method = ex.getRequestMethod();
        if (method.equals("PUT") || method.equals("DELETE")) {
            String raw = ex.getRequestURI().getRawPath();
            String prefix = "/" + BUCKET + "/";
            if (raw.startsWith(prefix)) {
                trackedKey = decode(raw.substring(prefix.length()));
                if (activeByKey.computeIfAbsent(trackedKey, k -> new AtomicInteger()).incrementAndGet() > 1)
                    sameKeyOverlaps.incrementAndGet();
            }
        }
        try {
            process(ex);
        } catch (RuntimeException e) {
            respond(ex, 500, xmlError("InternalError", e.toString()));
        } finally {
            if (trackedKey != null) activeByKey.get(trackedKey).decrementAndGet();
            inFlight.decrementAndGet();
            ex.close();
        }
    }

    private void process(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        byte[] body = ex.getRequestBody().readAllBytes();
        log.add(method + " " + rawPath + (rawQuery == null ? "" : "?" + rawQuery));

        if (latencyMillis > 0) sleep(latencyMillis);

        Fault fault = null;
        if (faultFilter.test(method + " " + rawPath)) fault = queuedFaults.poll();
        if (fault != null) {
            if (fault.reset()) return; // closing without a response resets the connection
            if (fault.hangMillis() > 0) { sleep(fault.hangMillis()); return; }
            respond(ex, fault.status(), xmlError(fault.code(), "injected"));
            return;
        }

        if (!verify(ex, method, rawPath, rawQuery, body)) {
            signatureFailures.incrementAndGet();
            respond(ex, 403, xmlError("SignatureDoesNotMatch", "bad signature"));
            return;
        }

        String prefix = "/" + BUCKET;
        if (!rawPath.equals(prefix) && !rawPath.startsWith(prefix + "/")) {
            respond(ex, 404, xmlError("NoSuchBucket", "no such bucket"));
            return;
        }
        String key = rawPath.length() <= prefix.length() + 1 ? "" : decode(rawPath.substring(prefix.length() + 1));
        Map<String, String> query = parseQuery(rawQuery);

        switch (method) {
            case "PUT" -> {
                mutations.add("PUT " + key);
                objects.put(key, new StoredObject(body, ex.getRequestHeaders().getFirst("Content-Type"),
                        ex.getRequestHeaders().getFirst("Cache-Control")));
                respond(ex, 200, new byte[0]);
            }
            case "GET" -> {
                if (key.isEmpty() && query.containsKey("list-type")) { list(ex, query); return; }
                StoredObject o = objects.get(key);
                if (o == null) respond(ex, 404, xmlError("NoSuchKey", "no such key"));
                else respond(ex, 200, o.data());
            }
            case "HEAD" -> {
                StoredObject o = objects.get(key);
                ex.sendResponseHeaders(o == null ? 404 : 200, -1);
            }
            case "DELETE" -> {
                mutations.add("DELETE " + key);
                objects.remove(key);
                ex.sendResponseHeaders(204, -1);
            }
            case "POST" -> {
                if (!query.containsKey("delete")) { respond(ex, 400, xmlError("InvalidRequest", "post")); return; }
                deleteBatch(ex, body);
            }
            default -> respond(ex, 405, xmlError("MethodNotAllowed", method));
        }
    }

    private boolean verify(HttpExchange ex, String method, String rawPath, String rawQuery, byte[] body) {
        var h = ex.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        String date = h.getFirst("x-amz-date");
        String declaredHash = h.getFirst("x-amz-content-sha256");
        if (auth == null || date == null || declaredHash == null) return false;
        if (!declaredHash.equals(SigV4Signer.sha256Hex(body))) return false;
        String signedHeaders = auth.substring(auth.indexOf("SignedHeaders=") + 14, auth.indexOf(", Signature="));
        Map<String, String> headers = new TreeMap<>();
        for (String name : signedHeaders.split(";")) {
            String value = name.equals("host") ? h.getFirst("Host") : h.getFirst(name);
            if (value == null) return false;
            headers.put(name, value);
        }
        java.time.Instant t = java.time.LocalDateTime.parse(date, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .toInstant(java.time.ZoneOffset.UTC);
        String expected = verifier.authorization(method, rawPath, parseQuery(rawQuery), headers, declaredHash, t);
        return expected.equals(auth);
    }

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        String delimiter = q.get("delimiter");
        String token = q.get("continuation-token");
        int max = Integer.parseInt(q.getOrDefault("max-keys", "1000"));
        TreeSet<String> keys = new TreeSet<>();
        TreeSet<String> prefixes = new TreeSet<>();
        for (String k : objects.keySet()) {
            if (!k.startsWith(prefix)) continue;
            if (delimiter != null) {
                int idx = k.indexOf(delimiter, prefix.length());
                if (idx >= 0) { prefixes.add(k.substring(0, idx + delimiter.length())); continue; }
            }
            keys.add(k);
        }
        List<String> page = new ArrayList<>();
        String next = null;
        for (String k : keys) {
            if (token != null && k.compareTo(token) <= 0) continue;
            if (page.size() == max) { next = page.get(page.size() - 1); break; }
            page.add(k);
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>");
        sb.append("<IsTruncated>").append(next != null).append("</IsTruncated>");
        if (next != null) sb.append("<NextContinuationToken>").append(S3Client.xmlEscape(next)).append("</NextContinuationToken>");
        for (String k : page) sb.append("<Contents><Key>").append(S3Client.xmlEscape(k)).append("</Key><Size>")
                .append(objects.get(k) == null ? 0 : objects.get(k).data().length).append("</Size></Contents>");
        if (token == null) for (String p : prefixes) sb.append("<CommonPrefixes><Prefix>").append(S3Client.xmlEscape(p)).append("</Prefix></CommonPrefixes>");
        sb.append("</ListBucketResult>");
        respond(ex, 200, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void deleteBatch(HttpExchange ex, byte[] body) throws IOException {
        String md5 = ex.getRequestHeaders().getFirst("Content-MD5");
        try {
            String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
            if (!actual.equals(md5)) { respond(ex, 400, xmlError("BadDigest", "md5")); return; }
        } catch (Exception e) { throw new IllegalStateException(e); }
        String xml = new String(body, StandardCharsets.UTF_8);
        var m = java.util.regex.Pattern.compile("<Key>(.*?)</Key>").matcher(xml);
        int count = 0;
        while (m.find()) {
            objects.remove(m.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"));
            count++;
        }
        if (count > 1000) { respond(ex, 400, xmlError("MalformedXML", "too many")); return; }
        respond(ex, 200, "<DeleteResult></DeleteResult>".getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private static byte[] xmlError(String code, String message) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + message
                + "</Message></Error>").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new TreeMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            m.put(decode(i < 0 ? part : part.substring(0, i)), i < 0 ? "" : decode(part.substring(i + 1)));
        }
        return m;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
