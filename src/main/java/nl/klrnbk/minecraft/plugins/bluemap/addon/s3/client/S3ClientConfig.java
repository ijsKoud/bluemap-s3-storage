package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import java.time.Duration;

/** Everything the {@link S3Client} needs. Secrets are kept out of {@link #toString()}. */
public record S3ClientConfig(
        String endpointUrl,
        String region,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        boolean pathStyle,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration readTimeout,
        int maxRetries,
        int readMaxRetries,
        int maxInFlight,
        int maxRequestsPerSecond,
        Duration backoffBase,
        Duration backoffCap) {

    /** Reads use the same timeout and retry count as writes. */
    public S3ClientConfig(String endpointUrl, String region, String bucket, String accessKeyId, String secretAccessKey,
                          boolean pathStyle, Duration connectTimeout, Duration requestTimeout, int maxRetries,
                          int maxInFlight, int maxRequestsPerSecond, Duration backoffBase, Duration backoffCap) {
        this(endpointUrl, region, bucket, accessKeyId, secretAccessKey, pathStyle, connectTimeout, requestTimeout,
                requestTimeout, maxRetries, maxRetries, maxInFlight, maxRequestsPerSecond, backoffBase, backoffCap);
    }

    /** Separate read timeout, same retry count. */
    public S3ClientConfig(String endpointUrl, String region, String bucket, String accessKeyId, String secretAccessKey,
                          boolean pathStyle, Duration connectTimeout, Duration requestTimeout, Duration readTimeout,
                          int maxRetries, int maxInFlight, int maxRequestsPerSecond, Duration backoffBase, Duration backoffCap) {
        this(endpointUrl, region, bucket, accessKeyId, secretAccessKey, pathStyle, connectTimeout, requestTimeout,
                readTimeout, maxRetries, maxRetries, maxInFlight, maxRequestsPerSecond, backoffBase, backoffCap);
    }

    @Override
    public String toString() {
        return "S3ClientConfig[endpoint=" + endpointUrl + ", region=" + region + ", bucket=" + bucket
                + ", pathStyle=" + pathStyle + "]";
    }
}
