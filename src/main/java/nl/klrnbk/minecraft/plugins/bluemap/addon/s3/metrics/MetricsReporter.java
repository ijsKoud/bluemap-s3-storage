package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.metrics;

import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.log.AddonLog;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.RequestGate;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Metrics;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue.WriteBehindStats;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Logs one INFO line every interval. All rates and percentiles are for the last interval, not since
 * start, so a stall shows up immediately. The most useful fields when render speed varies:
 * <ul>
 *   <li>{@code blocked}: time render threads spent waiting for buffer space. Above zero means uploads
 *       are slower than rendering and the render speed is now set by the upload speed.</li>
 *   <li>{@code queue}: how much is waiting; steadily growing means uploads cannot keep up.</li>
 *   <li>{@code PUT p50/p95/p99}: latency of uploads; {@code retries} and {@code failures} show throttling.</li>
 * </ul>
 */
public final class MetricsReporter implements AutoCloseable {

    private final S3Metrics metrics;
    private final RequestGate gate;
    private final Supplier<WriteBehindStats> stats;
    private final long intervalSeconds;
    private final ScheduledExecutorService executor;

    private final long[][] lastBuckets = new long[S3Metrics.Op.values().length][];
    private final long[] lastCounts = new long[S3Metrics.Op.values().length];
    private long lastRetries;
    private long lastTimeouts;
    private long lastIoErrors;
    private long lastFailures;
    private long lastBlockedNanos;
    private long lastCoalesced;
    private long lastNanos = System.nanoTime();

    public MetricsReporter(S3Metrics metrics, RequestGate gate, Supplier<WriteBehindStats> stats, int intervalSeconds) {
        this.metrics = metrics;
        this.gate = gate;
        this.stats = stats;
        this.intervalSeconds = intervalSeconds;
        for (S3Metrics.Op op : S3Metrics.Op.values()) lastBuckets[op.ordinal()] = metrics.buckets(op);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlueMap-S3-Metrics");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::logOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void logOnce() {
        try {
            AddonLog.info(nextLine());
        } catch (RuntimeException e) {
            AddonLog.warn("S3 metrics line failed: " + e);
        }
    }

    /** Builds the line for the interval since the previous call. Package-private for tests. */
    synchronized String nextLine() {
        long now = System.nanoTime();
        double seconds = Math.max(0.001, (now - lastNanos) / 1e9);
        lastNanos = now;
        WriteBehindStats s = stats.get();

        StringBuilder sb = new StringBuilder("S3 ").append(Math.round(seconds)).append("s: ");
        sb.append("queue=").append(s.queuedEntries()).append(" entries/").append(mb(s.queuedBytes())).append("MB");
        sb.append(" inflight=").append(gate.inFlight()).append(" uploading=").append(s.uploadsInFlight());

        for (S3Metrics.Op op : S3Metrics.Op.values()) {
            long count = metrics.count(op);
            long delta = count - lastCounts[op.ordinal()];
            lastCounts[op.ordinal()] = count;
            long[] buckets = metrics.buckets(op);
            long[] diff = new long[buckets.length];
            for (int i = 0; i < buckets.length; i++) diff[i] = buckets[i] - lastBuckets[op.ordinal()][i];
            lastBuckets[op.ordinal()] = buckets;
            if (delta == 0) continue;
            sb.append(" | ").append(op).append(' ').append(String.format(Locale.ROOT, "%.1f/s p50/p95/p99=%s/%s/%sms",
                    delta / seconds,
                    fmt(S3Metrics.percentileMillis(diff, 0.50)), fmt(S3Metrics.percentileMillis(diff, 0.95)),
                    fmt(S3Metrics.percentileMillis(diff, 0.99))));
        }

        long retries = metrics.retries();
        long failures = s.failedUploads();
        long timeouts = metrics.timeouts();
        long ioErrors = metrics.ioErrors();
        sb.append(" | retries=+").append(retries - lastRetries)
                .append(" timeouts=+").append(timeouts - lastTimeouts)
                .append(" ioErrors=+").append(ioErrors - lastIoErrors)
                .append(" failedUploads=+").append(failures - lastFailures)
                .append(" failedOps=").append(s.failedOpsPending());
        lastRetries = retries;
        lastTimeouts = timeouts;
        lastIoErrors = ioErrors;
        lastFailures = failures;

        long blocked = s.producerBlockedNanos();
        sb.append(" | spool=").append(mb(s.spoolBytes())).append("MB")
                .append(" coalesced=+").append(s.coalescedWrites() - lastCoalesced)
                .append(" blocked=+").append((blocked - lastBlockedNanos) / 1_000_000)
                .append("ms (total ").append(blocked / 1_000_000).append("ms)");
        lastBlockedNanos = blocked;
        lastCoalesced = s.coalescedWrites();
        return sb.toString();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String fmt(double ms) {
        return ms < 10 ? String.format(Locale.ROOT, "%.1f", ms) : Long.toString(Math.round(ms));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
