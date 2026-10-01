package nl.klrnbk.bluemap.s3.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class S3ClientContractTest {

    private FakeS3 fake;
    private S3Client client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeS3();
        client = new S3Client(fake.config(3, 16, 5000), new S3Metrics());
    }

    @AfterEach
    void tearDown() {
        client.close();
        fake.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetHeadDeleteRoundTripWithSignatureChecked() throws IOException {
        client.put("maps/w/tiles/0/x1/z2.prbm.gz", bytes("hello"), "application/octet-stream", "public, max-age=60");
        assertArrayEquals(bytes("hello"), client.get("maps/w/tiles/0/x1/z2.prbm.gz"));
        assertTrue(client.head("maps/w/tiles/0/x1/z2.prbm.gz"));
        var stored = fake.objects.get("maps/w/tiles/0/x1/z2.prbm.gz");
        assertEquals("application/octet-stream", stored.contentType());
        assertEquals("public, max-age=60", stored.cacheControl());
        client.delete("maps/w/tiles/0/x1/z2.prbm.gz");
        assertNull(client.get("maps/w/tiles/0/x1/z2.prbm.gz"));
        assertFalse(client.head("maps/w/tiles/0/x1/z2.prbm.gz"));
        client.delete("does/not/exist"); // idempotent
        assertEquals(0, fake.signatureFailures.get());
    }

    @Test
    void keysWithSpecialCharactersAreSignedCorrectly() throws IOException {
        String key = "root/assets/a b+c/é&=?.png";
        client.put(key, bytes("x"), "image/png", null);
        assertArrayEquals(bytes("x"), client.get(key));
        assertEquals(0, fake.signatureFailures.get());
    }

    @Test
    void wrongCredentialsAre403AndNotRetried() throws IOException {
        var cfg = fake.config(3, 4, 1000);
        try (S3Client bad = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), cfg.bucket(), cfg.accessKeyId(),
                "wrong", true, cfg.connectTimeout(), cfg.requestTimeout(), 3, 4, 1000, cfg.backoffBase(), cfg.backoffCap()), new S3Metrics())) {
            S3Exception e = assertThrows(S3Exception.class, () -> bad.get("k"));
            assertEquals(403, e.status());
            assertEquals("SignatureDoesNotMatch", e.code());
            assertEquals(1, fake.requests.get());
        }
    }

    @Test
    void errorMessagesNameTheOperationAndCarryTheServerMessage() {
        fake.inject(FakeS3.Fault.status(403, "AccessDenied"), 1);
        S3Exception e = assertThrows(S3Exception.class, () -> client.list("some/prefix/", "/", null, 1));
        assertEquals("GET bucket 'test-bucket' (list prefix 'some/prefix/') -> HTTP 403 AccessDenied: injected", e.getMessage());
        fake.inject(FakeS3.Fault.status(403, "AccessDenied"), 1);
        e = assertThrows(S3Exception.class, () -> client.get("a/key"));
        assertEquals("GET a/key -> HTTP 403 AccessDenied: injected", e.getMessage());
    }

    @Test
    void missingBucketIsAnErrorNotNull() {
        var cfg = fake.config(0, 4, 1000);
        S3Client other = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), "other-bucket", cfg.accessKeyId(),
                cfg.secretAccessKey(), true, cfg.connectTimeout(), cfg.requestTimeout(), 0, 4, 1000, cfg.backoffBase(), cfg.backoffCap()), new S3Metrics());
        try (other) {
            S3Exception e = assertThrows(S3Exception.class, () -> other.get("k"));
            assertEquals("NoSuchBucket", e.code());
        }
    }

    @Test
    void retriesOn503ThenSucceeds() throws IOException {
        fake.inject(FakeS3.Fault.status(503, "ServiceUnavailable"), 2);
        client.put("k", bytes("v"), "text/plain", null);
        assertEquals(3, fake.requests.get());
        assertEquals(2, client.metrics().retries());
        assertArrayEquals(bytes("v"), fake.data("k"));
    }

    @Test
    void retriesOnSlowDownAnd429() throws IOException {
        fake.inject(FakeS3.Fault.status(503, "SlowDown"), 1);
        fake.inject(FakeS3.Fault.status(429, "TooManyRequests"), 1);
        client.put("k", bytes("v"), "text/plain", null);
        assertArrayEquals(bytes("v"), fake.data("k"));
        assertTrue(fake.requests.get() >= 2);
    }

    @Test
    void givesUpAfterMaxRetries() {
        fake.inject(FakeS3.Fault.status(500, "InternalError"), 100);
        S3Exception e = assertThrows(S3Exception.class, () -> client.put("k", bytes("v"), "text/plain", null));
        assertEquals(500, e.status());
        assertEquals(4, fake.requests.get()); // 1 + maxRetries(3)
        assertTrue(e.getMessage().contains("gave up after 4 attempts"));
        assertFalse(e.getMessage().contains(FakeS3.SECRET_KEY));
    }

    @Test
    void doesNotRetry400() {
        fake.inject(FakeS3.Fault.status(400, "BadRequest"), 100);
        assertThrows(S3Exception.class, () -> client.put("k", bytes("v"), "text/plain", null));
        assertEquals(1, fake.requests.get());
    }

    @Test
    void retriesConnectionResets() throws IOException {
        fake.inject(FakeS3.Fault.connectionReset(), 2);
        client.put("k", bytes("v"), "text/plain", null);
        assertArrayEquals(bytes("v"), fake.data("k"));
        assertTrue(client.metrics().retries() >= 2);
    }

    @Test
    void retriesTimeouts() throws IOException {
        var cfg = fake.config(2, 4, 1000);
        try (S3Client fast = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), cfg.bucket(), cfg.accessKeyId(),
                cfg.secretAccessKey(), true, cfg.connectTimeout(), Duration.ofMillis(300), 2, 4, 1000, cfg.backoffBase(), cfg.backoffCap()), new S3Metrics())) {
            fake.inject(FakeS3.Fault.hang(1500), 1);
            fast.put("k", bytes("v"), "text/plain", null);
            assertArrayEquals(bytes("v"), fake.data("k"));
            assertEquals(1, fast.metrics().retries());
        }
    }

    @Test
    void readsUseTheShorterReadTimeoutAndAreCounted() throws Exception {
        var cfg = fake.config(2, 4, 1000);
        try (S3Client c = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), cfg.bucket(), cfg.accessKeyId(),
                cfg.secretAccessKey(), true, cfg.connectTimeout(), Duration.ofSeconds(5), Duration.ofMillis(300), 2, 4, 1000,
                cfg.backoffBase(), cfg.backoffCap()), new S3Metrics())) {
            fake.objects.put("k", new FakeS3.StoredObject(bytes("v"), null, null));
            fake.inject(FakeS3.Fault.hang(2000), 1);
            long t0 = System.nanoTime();
            assertArrayEquals(bytes("v"), c.get("k")); // first attempt hangs, read timeout 300 ms, retry succeeds
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500, "read must not wait for the 5 s write timeout");
            assertEquals(1, c.metrics().timeouts());
            assertEquals(0, c.metrics().ioErrors());
        }
    }

    @Test
    void writeTimeoutGrowsWithBodySize() throws Exception {
        var cfg = fake.config(0, 4, 1000);
        try (S3Client c = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), cfg.bucket(), cfg.accessKeyId(),
                cfg.secretAccessKey(), true, cfg.connectTimeout(), Duration.ofSeconds(1), 0, 4, 1000,
                cfg.backoffBase(), cfg.backoffCap()), new S3Metrics())) {
            fake.latencyMillis = 1500; // slower than the 1 s base timeout
            assertThrows(S3Exception.class, () -> c.put("small", new byte[10], "a/b", null));
            assertEquals(1, c.metrics().timeouts());
            c.put("big", new byte[2 << 20], "a/b", null); // 1 s + 2 s allowed for 2 MiB, so 1.5 s is fine
            assertEquals(2 << 20, fake.data("big").length);
            assertEquals(1, c.metrics().timeouts());
        }
    }

    @Test
    void readsRetryMoreOftenThanWrites() throws Exception {
        var cfg = fake.config(1, 4, 1000);
        try (S3Client c = new S3Client(new S3ClientConfig(cfg.endpointUrl(), cfg.region(), cfg.bucket(), cfg.accessKeyId(),
                cfg.secretAccessKey(), true, cfg.connectTimeout(), Duration.ofSeconds(5), Duration.ofMillis(200), 1, 6, 4, 1000,
                cfg.backoffBase(), cfg.backoffCap()), new S3Metrics())) {
            fake.objects.put("k", new FakeS3.StoredObject(bytes("v"), null, null));
            fake.inject(FakeS3.Fault.hang(1000), 5, r -> r.startsWith("GET"));
            assertArrayEquals(bytes("v"), c.get("k")); // 5 hung attempts, 6th succeeds (read-max-retries 6)
            assertEquals(5, c.metrics().timeouts());
            fake.inject(FakeS3.Fault.status(500, "InternalError"), 10, r -> r.startsWith("PUT"));
            assertThrows(S3Exception.class, () -> c.put("w", bytes("x"), "a/b", null));
            assertEquals(2, fake.log.stream().filter(l -> l.startsWith("PUT")).count(), "writes use max-retries 1");
        }
    }

    @Test
    void listPaginatesAndSupportsDelimiter() throws IOException {
        for (int i = 0; i < 25; i++) fake.objects.put(String.format("root/w/tiles/0/k%02d", i), new FakeS3.StoredObject(new byte[1], null, null));
        fake.objects.put("root/w/settings.json", new FakeS3.StoredObject(new byte[1], null, null));
        fake.objects.put("root/v/settings.json", new FakeS3.StoredObject(new byte[1], null, null));
        fake.objects.put("other/x", new FakeS3.StoredObject(new byte[1], null, null));

        List<String> all = new ArrayList<>();
        int pages = 0;
        String token = null;
        do {
            ListPage p = client.list("root/w/tiles/0/", null, token, 10);
            all.addAll(p.keys());
            token = p.nextToken();
            pages++;
        } while (token != null);
        assertEquals(25, all.size());
        assertEquals(3, pages);
        assertEquals(all.stream().sorted().toList(), all);

        ListPage dirs = client.list("root/", "/", null, 0);
        assertEquals(List.of("root/v/", "root/w/"), dirs.commonPrefixes());
        assertTrue(dirs.keys().isEmpty());

        List<String> viaAll = new ArrayList<>();
        client.listAll("root/w/tiles/", viaAll::add);
        assertEquals(25, viaAll.size());
    }

    @Test
    void deleteObjectsBatch() throws IOException {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String k = "b/k&" + i;
            keys.add(k);
            fake.objects.put(k, new FakeS3.StoredObject(new byte[1], null, null));
        }
        fake.objects.put("b/keep", new FakeS3.StoredObject(new byte[1], null, null));
        assertEquals(List.of(), client.deleteObjects(keys));
        assertEquals(Set.of("b/keep"), fake.objects.keySet());
        assertEquals(0, fake.signatureFailures.get());
        assertThrows(IllegalArgumentException.class, () -> client.deleteObjects(Collections.nCopies(1001, "x")));
    }

    @Test
    void rateLimiterSpacesRequests() throws Exception {
        try (S3Client slow = new S3Client(fake.config(0, 16, 100), new S3Metrics())) {
            long start = System.nanoTime();
            for (int i = 0; i < 31; i++) slow.head("k");
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 290, "30 intervals of 10ms expected, took " + millis + "ms");
        }
    }

    @Test
    void inFlightLimitIsHonouredAndReadsAreNotStarved() throws Exception {
        fake.latencyMillis = 100;
        try (S3Client limited = new S3Client(fake.config(0, 8, 10_000), new S3Metrics())) {
            ExecutorService pool = Executors.newFixedThreadPool(40);
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                writes.add(pool.submit(() -> { limited.put("w" + n, new byte[10], "a/b", null); return null; }));
            }
            Thread.sleep(150); // let writers saturate their share
            long t0 = System.nanoTime();
            limited.head("w0"); // a read must get the reserved slot without waiting for all writes
            long readMillis = (System.nanoTime() - t0) / 1_000_000;
            for (Future<?> f : writes) f.get();
            pool.shutdown();
            assertTrue(fake.maxInFlight.get() <= 8, "max in flight " + fake.maxInFlight.get());
            assertTrue(readMillis < 400, "read waited " + readMillis + "ms");
        }
    }
}
