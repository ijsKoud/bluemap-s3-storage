package nl.klrnbk.bluemap.s3.queue;

import nl.klrnbk.bluemap.s3.client.FakeS3;
import nl.klrnbk.bluemap.s3.client.S3Client;
import nl.klrnbk.bluemap.s3.client.S3Metrics;
import nl.klrnbk.bluemap.s3.storage.ObjectMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of the project: 8 producers write 2,000 objects of 5 to 50 KB each against a fake S3 with
 * 50 ms latency per request. Producers must finish in a small fraction of the time a synchronous
 * one-PUT-per-write implementation needs, the request rate must stay under the limit, and every object must
 * end up in the store exactly once with its latest content.
 */
class WriteBehindThroughputTest {

    static final int PRODUCERS = 8;
    static final int OBJECTS = 2000;
    static final int REWRITTEN = 100; // the first objects of each producer are written twice
    static final int RATE_LIMIT = 600;
    static final ObjectMeta META = new ObjectMeta("application/octet-stream", "public, max-age=60");

    @TempDir Path tmp;

    static byte[] content(int producer, int index, int version) {
        Random r = new Random(producer * 1_000_003L + index * 31L + version);
        byte[] data = new byte[5_000 + r.nextInt(45_001)];
        r.nextBytes(data);
        data[0] = (byte) version;
        return data;
    }

    static String key(int producer, int index) {
        return "maps/world/tiles/0/p" + producer + "/o" + index;
    }

    @Test
    void producersAreNotBlockedByTheNetwork() throws Exception {
        try (FakeS3 fake = new FakeS3()) {
            fake.latencyMillis = 50;
            S3Client client = new S3Client(fake.config(2, 64, RATE_LIMIT), new S3Metrics());

            // Calibrate the synchronous baseline on this machine: one PUT at a time, as the phase 3 store does.
            int sample = 40;
            long s0 = System.nanoTime();
            for (int i = 0; i < sample; i++) client.put("calibration/" + i, content(99, i, 0), "a/b", null);
            double syncPerWriteNanos = (System.nanoTime() - s0) / (double) sample;
            double syncEstimateMillis = syncPerWriteNanos * OBJECTS / 1e6; // each producer does its 2000 writes one after another
            fake.objects.clear();
            fake.requestTimesNanos.clear();
            fake.mutations.clear();

            WriteBehindObjectStore store = new WriteBehindObjectStore(client, new WriteBehindConfig(64, 1L << 30, 20_000,
                    true, tmp.resolve("spool"), 4L << 30, Duration.ofMinutes(2), Duration.ofSeconds(30)));

            ExecutorService producers = Executors.newFixedThreadPool(PRODUCERS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int p = 0; p < PRODUCERS; p++) {
                int id = p;
                results.add(producers.submit(() -> {
                    go.await();
                    long own = 0;
                    for (int i = 0; i < OBJECTS; i++) {
                        byte[] data = content(id, i, 1); // generating the data is the producer's own work
                        long t = System.nanoTime();
                        store.put(key(id, i), data, META);
                        own += System.nanoTime() - t;
                    }
                    for (int i = 0; i < REWRITTEN; i++) store.put(key(id, i), content(id, i, 2), META);
                    return own;
                }));
            }
            long start = System.nanoTime();
            go.countDown();
            long enqueueNanos = 0;
            for (Future<Long> f : results) enqueueNanos += f.get();
            double producersMillis = (System.nanoTime() - start) / 1e6;
            producers.shutdown();

            long drainStart = System.nanoTime();
            store.close(); // flushes everything
            double drainMillis = (System.nanoTime() - drainStart) / 1e6;

            System.out.printf("throughput: producers %.0f ms (sync estimate %.0f ms = %.1f%%), avg put() %.1f us, drain %.0f ms, "
                            + "requests %d, blocked %d ms%n", producersMillis, syncEstimateMillis,
                    100 * producersMillis / syncEstimateMillis, enqueueNanos / 1000.0 / (PRODUCERS * OBJECTS),
                    drainMillis, fake.requests.get(), store.stats().producerBlockedNanos() / 1_000_000);

            assertTrue(producersMillis < 0.10 * syncEstimateMillis,
                    "producers took " + producersMillis + " ms, synchronous estimate " + syncEstimateMillis + " ms");
            assertEquals(0, store.stats().producerBlockedNanos(), "this scenario must not hit backpressure");

            // every object exactly once, latest content
            assertEquals(PRODUCERS * OBJECTS, fake.objects.size());
            for (int p = 0; p < PRODUCERS; p++) {
                for (int i = 0; i < OBJECTS; i++) {
                    byte[] expected = content(p, i, i < REWRITTEN ? 2 : 1);
                    assertArrayEquals(expected, fake.data(key(p, i)), key(p, i));
                }
            }
            assertEquals(0, fake.sameKeyOverlaps.get());
            long puts = fake.mutations.stream().filter(m -> m.startsWith("PUT")).count();
            assertTrue(puts >= PRODUCERS * OBJECTS && puts <= PRODUCERS * (OBJECTS + REWRITTEN), "puts " + puts);

            // rate limit: no one second window may exceed the limit (5% tolerance for timer jitter on the server side)
            long[] times = fake.requestTimesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            int max = 0;
            for (int lo = 0, hi = 0; hi < times.length; hi++) {
                while (times[hi] - times[lo] >= 1_000_000_000L) lo++;
                max = Math.max(max, hi - lo + 1);
            }
            System.out.println("throughput: max requests in any 1 s window = " + max + " (limit " + RATE_LIMIT + ")");
            assertTrue(max <= RATE_LIMIT * 1.05, "max requests per second " + max);
            client.close();
        }
    }
}
