package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for S3-style services (URI paths are encoded once, never twice).
 * Dependency-free: only javax.crypto and java.security.
 */
public final class SigV4Signer {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKeyId;
    private final byte[] secretKeyBytes;
    private final String region;
    private final String service;

    public SigV4Signer(String accessKeyId, String secretAccessKey, String region, String service) {
        this.accessKeyId = accessKeyId;
        this.secretKeyBytes = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        this.region = region;
        this.service = service;
    }

    public static String amzDate(Instant time) {
        return AMZ_DATE.format(time);
    }

    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256(data));
    }

    /**
     * Builds the value of the {@code Authorization} header.
     *
     * @param canonicalPath already URI-encoded absolute path
     * @param query         raw (not yet encoded) query parameters
     * @param headers       all headers to sign; names are lower-cased here, values trimmed
     */
    public String authorization(String method, String canonicalPath, Map<String, String> query,
                                Map<String, String> headers, String payloadSha256Hex, Instant time) {
        SortedMap<String, String> sortedHeaders = new TreeMap<>();
        headers.forEach((k, v) -> sortedHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.trim().replaceAll("\\s+", " ")));

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (var e : sortedHeaders.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (!signedHeaders.isEmpty()) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + '\n'
                + canonicalPath + '\n'
                + canonicalQuery(query) + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadSha256Hex;

        String dateStamp = DATE_STAMP.format(time);
        String scope = dateStamp + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(time) + '\n' + scope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmac(secretKeyBytes, dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HEX.formatHex(hmac(kSigning, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** Sorted by encoded name, then encoded value, joined as name=value with '&'. */
    public static String canonicalQuery(Map<String, String> query) {
        SortedMap<String, String> encoded = new TreeMap<>();
        query.forEach((k, v) -> encoded.put(uriEncode(k, true), uriEncode(v, true)));
        StringBuilder sb = new StringBuilder();
        for (var e : encoded.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** RFC 3986 encoding as required by SigV4: unreserved characters stay, everything else is %XX. */
    public static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
