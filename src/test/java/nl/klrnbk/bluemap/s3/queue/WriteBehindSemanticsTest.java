package nl.klrnbk.bluemap.s3.queue;

import nl.klrnbk.bluemap.s3.client.FakeS3;
import nl.klrnbk.bluemap.s3.client.S3Client;
import nl.klrnbk.bluemap.s3.client.S3Metrics;
import nl.klrnbk.bluemap.s3.storage.ObjectMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WriteBehindSemanticsTest {

    static final ObjectMeta META = new ObjectMeta("application/octet-stream", "public, max-age=60");

    @TempDir Path tmp;
    FakeS3 fake;
    S3Client client;
    WriteBehindObjectStore store;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeS3();
        client = new S3Client(fake.config(1, 16, 5000), new S3Metrics());
        store = newStore(4, 1 << 20, 1000, true, 1 << 30);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) store.close();
        client.close();
        fake.close();
    }

    WriteBehindObjectStore newStore(int threads, long maxBytes, int maxEntries, boolean spool, long spoolMax) throws IOException {
        return new WriteBehindObjectStore(client, new WriteBehindConfig(threads, maxBytes, maxEntries, spool,
                tmp.resolve("spool"), spoolMax, Duration.ofSeconds(20), Duration.ofMillis(100)));
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    long spoolFiles() {
        try (Stream<Path> s = Files.list(tmp.resolve("spool"))) {
            return s.filter(p -> p.toString().endsWith(".op")).count();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void putReturnsImmediatelyAndReadYourWritesWithoutNetwork() throws Exception {
        fake.latencyMillis = 300;
        long t0 = System.nanoTime();
        store.put("k", b("v1"), META);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 100, "put must not wait for the network");
        fake.log.clear();
        assertArrayEquals(b("v1"), store.get("k"));
        assertTrue(store.exists("k"));
        assertTrue(fake.log.stream().noneMatch(l -> l.startsWith("GET /test-bucket/k") || l.startsWith("HEAD")), fake.log.toString());
        await(() -> fake.objects.containsKey("k"), "upload");
        assertEquals("public, max-age=60", fake.objects.get("k").cacheControl());
    }

    @Test
    void latestWriteWinsAndQueuedWritesAreCoalesced() throws Exception {
        store.close();
        store = newStore(1, 1 << 20, 1000, true, 1 << 30);
        fake.latencyMillis = 100;
        store.put("blocker", b("x"), META); // occupies the single worker
        Thread.sleep(30);
        for (int i = 1; i <= 20; i++) store.put("k", b("v" + i), META);
        assertArrayEquals(b("v20"), store.get("k"));
        await(() -> store.pendingKeys() == 0, "drain");
        assertArrayEquals(b("v20"), fake.data("k"));
        assertEquals(1, fake.mutations.stream().filter(m -> m.equals("PUT k")).count(), "queued writes must coalesce");
        assertTrue(store.stats().coalescedWrites() >= 19);
    }

    @Test
    void newerWriteWaitsBehindInFlightUploadNeverParallel() throws Exception {
        fake.latencyMillis = 150;
        store.put("k", b("v1"), META);
        Thread.sleep(50); // v1 is now in flight
        store.put("k", b("v2"), META);
        assertArrayEquals(b("v2"), store.get("k"));
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(0, fake.sameKeyOverlaps.get());
        assertEquals(List.of("PUT k", "PUT k"), List.copyOf(fake.mutations));
        assertArrayEquals(b("v2"), fake.data("k"));
    }

    @Test
    void putThenDeleteOrdering() throws Exception {
        fake.latencyMillis = 100;
        store.put("k", b("v"), META);
        Thread.sleep(40); // PUT in flight
        store.delete("k");
        assertNull(store.get("k"));
        assertFalse(store.exists("k"));
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(List.of("PUT k", "DELETE k"), List.copyOf(fake.mutations));
        assertFalse(fake.objects.containsKey("k"));
    }

    @Test
    void deleteThenPutOrdering() throws Exception {
        fake.objects.put("k", new FakeS3.StoredObject(b("old"), null, null));
        fake.latencyMillis = 100;
        store.delete("k");
        Thread.sleep(40); // DELETE in flight
        store.put("k", b("new"), META);
        assertArrayEquals(b("new"), store.get("k"));
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(List.of("DELETE k", "PUT k"), List.copyOf(fake.mutations));
        assertArrayEquals(b("new"), fake.data("k"));
    }

    @Test
    void streamMergesPendingPutsAndHidesPendingDeletes() throws Exception {
        for (String k : List.of("p/a", "p/b", "p/c", "other/x"))
            fake.objects.put(k, new FakeS3.StoredObject(b("s3"), null, null));
        fake.latencyMillis = 500; // keep everything pending
        store.delete("p/b");
        store.put("p/d", b("new"), META);
        store.put("p/a", b("newer"), META); // exists remotely and pending: must appear once
        try (Stream<String> s = store.listKeys("p/")) {
            assertEquals(Set.of("p/a", "p/c", "p/d"), s.collect(Collectors.toSet()));
        }
        try (Stream<String> s = store.listKeys("p/")) {
            assertEquals(3, s.count(), "no duplicates");
        }
        assertEquals(List.of("other/", "p/"), store.listPrefixes(""));
    }

    @Test
    void closeFlushesEverything() throws Exception {
        fake.latencyMillis = 20;
        for (int i = 0; i < 200; i++) store.put("k" + i, b("v" + i), META);
        store.close();
        assertEquals(200, fake.objects.size());
        assertEquals(0, spoolFiles());
        assertThrows(IOException.class, () -> store.put("late", b("x"), META));
        store = null;
    }

    @Test
    void spoolReplayAfterSimulatedCrash() throws Exception {
        fake.inject(FakeS3.Fault.hang(60_000), 4); // the first uploads hang, then the "process dies"
        store.close();
        client.close();
        client = new S3Client(fake.config(0, 16, 5000), new S3Metrics());
        store = newStore(4, 1 << 20, 1000, true, 1 << 30);
        for (int i = 0; i < 50; i++) store.put("k" + i, b("v" + i), META);
        store.put("k7", b("v7-latest"), META);
        store.delete("k8");
        fake.objects.put("k8", new FakeS3.StoredObject(b("old"), null, null));
        Thread.sleep(50);
        store.abandonForTest(); // crash: no flush
        assertTrue(spoolFiles() > 0, "unflushed ops must be in the spool");
        Set<String> uploadedBeforeCrash = new HashSet<>(fake.objects.keySet());

        store = newStore(4, 1 << 20, 1000, true, 1 << 30); // restart: replay
        await(() -> store.pendingKeys() == 0, "replay drain");
        for (int i = 0; i < 50; i++) {
            if (i == 8) continue;
            byte[] expected = i == 7 ? b("v7-latest") : b("v" + i);
            assertArrayEquals(expected, fake.data("k" + i), "k" + i + " (uploaded before crash: " + uploadedBeforeCrash.contains("k" + i) + ")");
        }
        assertNull(fake.data("k8"), "replayed delete must win over the old remote object");
        await(() -> spoolFiles() == 0, "spool cleanup");
    }

    @Test
    void spoolRespectsMaxBytesAndFallsBackToMemory() throws Exception {
        store.close();
        store = newStore(1, 1 << 20, 1000, true, 2000);
        fake.latencyMillis = 200;
        for (int i = 0; i < 10; i++) store.put("k" + i, new byte[500], META);
        assertTrue(store.stats().spoolBytes() <= 2000, "spool bytes " + store.stats().spoolBytes());
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(10, fake.objects.size());
        assertEquals(0, store.stats().spoolBytes());
    }

    @Test
    void backpressureBlocksProducersWhenBufferIsFullAndCountsBlockedTime() throws Exception {
        store.close();
        store = newStore(2, 1 << 20, 3, false, 0); // at most 3 entries
        fake.latencyMillis = 100;
        long t0 = System.nanoTime();
        for (int i = 0; i < 12; i++) store.put("k" + i, b("v"), META);
        long producerMillis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(producerMillis >= 300, "producer must have been throttled, took " + producerMillis);
        assertTrue(store.stats().producerBlockedNanos() > 100_000_000L);
        assertTrue(store.stats().queuedEntries() <= 3);
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(12, fake.objects.size());
        assertEquals(0, store.stats().queuedEntries());
        assertEquals(0, store.stats().queuedBytes());
    }

    @Test
    void oversizedOpIsAdmittedWhenBufferIsEmpty() throws Exception {
        store.close();
        store = newStore(1, 100, 10, false, 0);
        store.put("big", new byte[10_000], META);
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(10_000, fake.data("big").length);
    }

    @Test
    void failedUploadsAreKeptInSpoolServedFromThereAndRetried() throws Exception {
        fake.inject(FakeS3.Fault.status(500, "InternalError"), 1000, r -> r.startsWith("PUT") && r.endsWith("/bad"));
        store.put("bad", b("precious"), META);
        store.put("good", b("fine"), META);
        await(() -> store.stats().failedOpsPending() == 1, "failure recorded");
        await(() -> fake.objects.containsKey("good"), "good upload");
        assertTrue(store.stats().failedUploads() > 0);
        assertEquals(1, spoolFiles(), "failed op must stay in the spool");
        assertEquals(0, store.stats().queuedBytes(), "evicted op must free buffer space");
        assertArrayEquals(b("precious"), store.get("bad"), "reads are served from the spool");
        assertTrue(store.exists("bad"));

        // heal: the periodic retry delivers it
        fake.clearFaults();
        await(() -> fake.objects.containsKey("bad"), "retry after heal");
        assertArrayEquals(b("precious"), fake.data("bad"));
        await(() -> store.pendingKeys() == 0 && spoolFiles() == 0, "cleanup");
        assertEquals(0, store.stats().failedOpsPending());
    }

    @Test
    void persistentlyFailingKeysCannotOccupyAllUploadThreads() throws Exception {
        store.close();
        store = newStore(8, 1 << 20, 1000, true, 1 << 30); // retry cap = 8 / 8 = 1 concurrent failed op
        fake.inject(FakeS3.Fault.status(500, "InternalError"), 10_000, r -> r.startsWith("PUT") && r.contains("/bad"));
        for (int i = 0; i < 6; i++) store.put("bad" + i, b("x"), META);
        await(() -> store.stats().failedOpsPending() == 6, "all six failed");

        // from now on bad keys hang for 150 ms; only the capped retries may be in flight
        fake.clearFaults();
        fake.inject(FakeS3.Fault.hang(150), 10_000, r -> r.startsWith("PUT") && r.contains("/bad"));
        fake.maxInFlight.set(0);
        Thread.sleep(1500);
        assertTrue(fake.maxInFlight.get() <= 1, "failed ops retried in parallel: " + fake.maxInFlight.get());

        // healthy uploads are not starved by the failing ones
        long t0 = System.nanoTime();
        store.put("healthy", b("ok"), META);
        await(() -> fake.objects.containsKey("healthy"), "healthy upload");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000);

        // when the bucket recovers they all arrive
        fake.clearFaults();
        await(() -> store.pendingKeys() == 0, "recovery");
        for (int i = 0; i < 6; i++) assertArrayEquals(b("x"), fake.data("bad" + i));
    }

    @Test
    void deletePrefixDiscardsPendingOpsAndRemovesRemoteObjects() throws Exception {
        fake.objects.put("m/old1", new FakeS3.StoredObject(b("1"), null, null));
        fake.objects.put("m/old2", new FakeS3.StoredObject(b("2"), null, null));
        fake.objects.put("other/keep", new FakeS3.StoredObject(b("3"), null, null));
        fake.latencyMillis = 100;
        store.put("m/new", b("x"), META);
        store.put("other/pending", b("y"), META);
        assertTrue(store.deletePrefix("m/", p -> true));
        assertNull(store.get("m/new"));
        await(() -> store.pendingKeys() == 0, "drain");
        assertEquals(Set.of("other/keep", "other/pending"), fake.objects.keySet());
    }

    @Test
    void concurrentProducersSameKeysConvergeToLastWrite() throws Exception {
        fake.latencyMillis = 5;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            fs.add(pool.submit(() -> {
                for (int round = 0; round < 50; round++)
                    for (int k = 0; k < 10; k++) store.put("shared" + k, b("t" + id + "r" + round), META);
                for (int k = 0; k < 10; k++) store.put("final" + id + "-" + k, b("done"), META);
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        // a single last writer decides the remaining state of the shared keys
        for (int k = 0; k < 10; k++) store.put("shared" + k, b("LAST"), META);
        await(() -> store.pendingKeys() == 0, "drain");
        for (int k = 0; k < 10; k++) assertArrayEquals(b("LAST"), fake.data("shared" + k));
        assertEquals(0, fake.sameKeyOverlaps.get());
        assertEquals(10 + 80, fake.objects.size());
        pool.shutdown();
    }
}
