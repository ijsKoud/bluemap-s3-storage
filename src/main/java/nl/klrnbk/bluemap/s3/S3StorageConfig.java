package nl.klrnbk.bluemap.s3;

import de.bluecolored.bluemap.common.config.ConfigurationException;
import de.bluecolored.bluemap.common.config.storage.StorageConfig;
import de.bluecolored.bluemap.common.debug.DebugDump;
import de.bluecolored.bluemap.core.storage.Storage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.Key;
import nl.klrnbk.bluemap.s3.client.S3Client;
import nl.klrnbk.bluemap.s3.client.S3ClientConfig;
import nl.klrnbk.bluemap.s3.client.S3Metrics;
import nl.klrnbk.bluemap.s3.storage.DirectObjectStore;
import nl.klrnbk.bluemap.s3.storage.ObjectKinds;
import nl.klrnbk.bluemap.s3.storage.S3Storage;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.nio.file.Path;
import java.time.Duration;

/** Configuration of the {@code klrnbk-bluemap:s3} storage type (keys are kebab-case in the file). */
@SuppressWarnings({"FieldMayBeFinal", "unused"})
@ConfigSerializable
public class S3StorageConfig extends StorageConfig {

    @Comment("S3 endpoint. Hetzner Object Storage Helsinki: https://hel1.your-objectstorage.com")
    private String endpointUrl = "https://hel1.your-objectstorage.com";

    @Comment("Signing region")
    private String region = "hel1";

    @Comment("Bucket name")
    private String bucketName = "";

    @DebugDump(exclude = true)
    private String accessKeyId = "";

    @DebugDump(exclude = true)
    private String secretAccessKey = "";

    @Comment("Use https://endpoint/bucket/key instead of https://bucket.endpoint/key")
    private boolean forcePathStyle = false;

    @Comment("Key prefix inside the bucket. \"\" or \".\" means the bucket root. Same semantics as the TheMeinerLP addon.")
    private String rootPath = "";

    @Comment("Compression of hires tiles and textures: none, gzip, deflate, zstd, lz4. Same as BlueMap's file storage.")
    private String compression = "gzip";

    @Comment("""
            Directory (relative to the server working dir) holding the render state.
            MUST be persistent and backed up together with the world.""")
    private String renderStatePath = "bluemap/rstate-s3";

    @Comment("Number of upload worker threads")
    private int uploadThreads = 16;

    @Comment("Maximum number of concurrent HTTP requests (uploads and reads)")
    private int maxInFlightRequests = 32;

    @Comment("Global request rate limit (Hetzner allows 750/s per bucket and per source IP)")
    private int maxRequestsPerSecond = 600;

    @Comment("Write-behind buffer limit in bytes; producers block when it is full")
    private long writeBufferMaxBytes = 268_435_456L;

    @Comment("Write-behind buffer limit in entries; producers block when it is full")
    private int writeBufferMaxEntries = 20_000;

    @Comment("Persist pending writes to local disk before acknowledging them (survives crashes)")
    private boolean spoolEnabled = true;

    @Comment("Spool directory, relative to the server working dir")
    private String spoolPath = "bluemap/s3-spool";

    @Comment("Maximum spool size in bytes; beyond it writes are memory-only (with a warning)")
    private long spoolMaxBytes = 2_147_483_648L;

    @Comment("Per-request timeout in seconds")
    private int requestTimeoutSeconds = 30;

    @Comment("Connect timeout in seconds")
    private int connectTimeoutSeconds = 10;

    @Comment("Retries per request after the first attempt (I/O errors, 429, 5xx, SlowDown)")
    private int maxRetries = 5;

    @Comment("How long close() waits for the queue to drain at shutdown")
    private int shutdownFlushTimeoutSeconds = 120;

    @Comment("Cache-Control header for hires and lowres tiles")
    private String tileCacheControl = "public, max-age=60";

    @Comment("Cache-Control header for settings, textures, markers, players and assets")
    private String metaCacheControl = "no-cache";

    @Comment("Interval of the metrics log line in seconds, 0 disables it")
    private int metricsLogIntervalSeconds = 30;

    @Comment("How long the list of map ids is cached, in seconds")
    private int listCacheTtlSeconds = 300;

    public String getEndpointUrl() { return endpointUrl; }
    public String getRegion() { return region; }
    public String getBucketName() { return bucketName; }
    public String getAccessKeyId() { return accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public boolean isForcePathStyle() { return forcePathStyle; }
    public String getRootPath() { return rootPath; }
    public String getRenderStatePath() { return renderStatePath; }
    public int getUploadThreads() { return uploadThreads; }
    public int getMaxInFlightRequests() { return maxInFlightRequests; }
    public int getMaxRequestsPerSecond() { return maxRequestsPerSecond; }
    public long getWriteBufferMaxBytes() { return writeBufferMaxBytes; }
    public int getWriteBufferMaxEntries() { return writeBufferMaxEntries; }
    public boolean isSpoolEnabled() { return spoolEnabled; }
    public String getSpoolPath() { return spoolPath; }
    public long getSpoolMaxBytes() { return spoolMaxBytes; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public int getShutdownFlushTimeoutSeconds() { return shutdownFlushTimeoutSeconds; }
    public String getTileCacheControl() { return tileCacheControl; }
    public String getMetaCacheControl() { return metaCacheControl; }
    public int getMetricsLogIntervalSeconds() { return metricsLogIntervalSeconds; }
    public int getListCacheTtlSeconds() { return listCacheTtlSeconds; }

    public Compression getCompression() throws ConfigurationException {
        Compression c = Compression.REGISTRY.get(Key.parse(compression, Key.BLUEMAP_NAMESPACE));
        if (c == null) throw new ConfigurationException("Unknown compression: " + compression);
        return c;
    }

    /** Checks the values; throws a {@link ConfigurationException} describing the first problem. */
    public void validate() throws ConfigurationException {
        require(!endpointUrl.isBlank(), "endpoint-url is required");
        require(!region.isBlank(), "region is required");
        require(!bucketName.isBlank(), "bucket-name is required");
        require(!accessKeyId.isBlank(), "access-key-id is required");
        require(!secretAccessKey.isBlank(), "secret-access-key is required");
        require(!renderStatePath.isBlank(), "render-state-path is required");
        require(uploadThreads >= 1, "upload-threads must be >= 1");
        require(maxInFlightRequests >= 2, "max-in-flight-requests must be >= 2");
        require(maxRequestsPerSecond >= 1, "max-requests-per-second must be >= 1");
        require(writeBufferMaxBytes >= 1, "write-buffer-max-bytes must be >= 1");
        require(writeBufferMaxEntries >= 1, "write-buffer-max-entries must be >= 1");
        require(requestTimeoutSeconds >= 1, "request-timeout-seconds must be >= 1");
        require(connectTimeoutSeconds >= 1, "connect-timeout-seconds must be >= 1");
        require(maxRetries >= 0, "max-retries must be >= 0");
        require(shutdownFlushTimeoutSeconds >= 0, "shutdown-flush-timeout-seconds must be >= 0");
        require(metricsLogIntervalSeconds >= 0, "metrics-log-interval-seconds must be >= 0");
        require(listCacheTtlSeconds >= 0, "list-cache-ttl-seconds must be >= 0");
        require(!spoolEnabled || !spoolPath.isBlank(), "spool-path is required when spool-enabled is true");
        getCompression();
        try {
            java.net.URI uri = java.net.URI.create(endpointUrl);
            require(("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null,
                    "endpoint-url must be an http(s) URL");
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("endpoint-url is not a valid URL");
        }
    }

    public KeyLayout keyLayout() throws ConfigurationException {
        return new KeyLayout(rootPath, getCompression().getFileSuffix());
    }

    @Override
    public Storage createStorage() throws ConfigurationException {
        validate();
        S3Client client = new S3Client(clientConfig(), new S3Metrics());
        KeyLayout layout = keyLayout();
        Compression compression = getCompression();
        return new S3Storage(client, new DirectObjectStore(client), layout, compression,
                new ObjectKinds(tileCacheControl, metaCacheControl), Path.of(renderStatePath), listCacheTtlSeconds);
    }

    public S3ClientConfig clientConfig() {
        return new S3ClientConfig(endpointUrl, region, bucketName, accessKeyId, secretAccessKey, forcePathStyle,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds), maxRetries,
                maxInFlightRequests, maxRequestsPerSecond, Duration.ofMillis(100), Duration.ofSeconds(10));
    }

    private static void require(boolean ok, String message) throws ConfigurationException {
        if (!ok) throw new ConfigurationException(message);
    }
}
