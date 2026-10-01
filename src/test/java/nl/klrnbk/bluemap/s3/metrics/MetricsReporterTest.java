package nl.klrnbk.bluemap.s3.metrics;

import nl.klrnbk.bluemap.s3.client.RequestGate;
import nl.klrnbk.bluemap.s3.client.S3Metrics;
import nl.klrnbk.bluemap.s3.queue.WriteBehindStats;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MetricsReporterTest {

    @Test
    void lineShowsIntervalValuesNotLifetimeTotals() {
        S3Metrics metrics = new S3Metrics();
        AtomicReference<WriteBehindStats> stats = new AtomicReference<>(
                new WriteBehindStats(120, 5 * 1048576L, 7, 0, 0, 0, 0, 2 * 1048576L, 0));
        try (MetricsReporter r = new MetricsReporter(metrics, new RequestGate(8, 100), stats::get, 3600)) {
            for (int i = 0; i < 100; i++) metrics.record(S3Metrics.Op.PUT, 40_000_000L, true); // 40 ms
            metrics.retry();
            stats.set(new WriteBehindStats(300, 9 * 1048576L, 12, 100, 5, 2, 1, 3 * 1048576L, 2_500_000_000L));
            String first = r.nextLine();
            assertTrue(first.contains("queue=300 entries/9.0MB"), first);
            assertTrue(first.contains("PUT "), first);
            assertTrue(first.contains("retries=+1"), first);
            assertTrue(first.contains("failedUploads=+2 failedOps=1"), first);
            assertTrue(first.contains("blocked=+2500ms (total 2500ms)"), first);
            assertTrue(first.contains("spool=3.0MB"), first);
            assertFalse(first.contains("GET "), "operations without samples are left out: " + first);

            String second = r.nextLine(); // nothing happened since
            assertFalse(second.contains("PUT "), second);
            assertTrue(second.contains("retries=+0"), second);
            assertTrue(second.contains("blocked=+0ms (total 2500ms)"), second);
        }
    }

    @Test
    void percentilesComeFromTheInterval() {
        S3Metrics metrics = new S3Metrics();
        for (int i = 0; i < 1000; i++) metrics.record(S3Metrics.Op.GET, 1_000_000L, true); // 1 ms
        try (MetricsReporter r = new MetricsReporter(metrics, new RequestGate(8, 100),
                () -> new WriteBehindStats(0, 0, 0, 0, 0, 0, 0, 0, 0), 3600)) {
            for (int i = 0; i < 10; i++) metrics.record(S3Metrics.Op.GET, 500_000_000L, true); // 500 ms
            String line = r.nextLine();
            // the old 1 ms samples must not dilute the interval: p50 is about 500 ms
            var m = java.util.regex.Pattern.compile("GET [\\d.]+/s p50/p95/p99=(\\d+)/").matcher(line);
            assertTrue(m.find(), line);
            assertTrue(Integer.parseInt(m.group(1)) >= 256, line);
        }
    }
}
