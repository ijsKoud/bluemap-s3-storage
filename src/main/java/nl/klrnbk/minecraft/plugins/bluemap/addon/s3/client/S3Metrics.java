package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import java.util.concurrent.atomic.LongAdder;

/** Lock-free request counters and latency histograms (log2 buckets of microseconds). */
public final class S3Metrics {

    public enum Op { GET, HEAD, PUT, DELETE, LIST, DELETE_BATCH }

    private static final int BUCKETS = 40;

    private final LongAdder[][] histogram = new LongAdder[Op.values().length][BUCKETS];
    private final LongAdder[] count = new LongAdder[Op.values().length];
    private final LongAdder[] failures = new LongAdder[Op.values().length];
    private final LongAdder retries = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder ioErrors = new LongAdder();

    public S3Metrics() {
        for (int i = 0; i < Op.values().length; i++) {
            count[i] = new LongAdder();
            failures[i] = new LongAdder();
            for (int b = 0; b < BUCKETS; b++) histogram[i][b] = new LongAdder();
        }
    }

    /** Records one finished logical operation (including its retries). */
    public void record(Op op, long nanos, boolean success) {
        long micros = Math.max(1, nanos / 1000);
        int bucket = Math.min(BUCKETS - 1, 63 - Long.numberOfLeadingZeros(micros));
        histogram[op.ordinal()][bucket].increment();
        count[op.ordinal()].increment();
        if (!success) failures[op.ordinal()].increment();
    }

    public void retry() {
        retries.increment();
    }

    /** A single attempt hit its timeout (connection or response did not arrive in time). */
    public void timeout() {
        timeouts.increment();
    }

    /** A single attempt failed with a connection level error other than a timeout. */
    public void ioError() {
        ioErrors.increment();
    }

    public long timeouts() {
        return timeouts.sum();
    }

    public long ioErrors() {
        return ioErrors.sum();
    }

    public long count(Op op) {
        return count[op.ordinal()].sum();
    }

    public long failures(Op op) {
        return failures[op.ordinal()].sum();
    }

    public long retries() {
        return retries.sum();
    }

    /** Approximate percentile (upper bound of the log2 bucket) in milliseconds, 0 if no samples. */
    public double percentileMillis(Op op, double p) {
        return percentileMillis(buckets(op), p);
    }

    /** Copy of the latency histogram of an operation; diff two copies to get an interval. */
    public long[] buckets(Op op) {
        long[] copy = new long[BUCKETS];
        for (int b = 0; b < BUCKETS; b++) copy[b] = histogram[op.ordinal()][b].sum();
        return copy;
    }

    public static double percentileMillis(long[] buckets, double p) {
        long total = 0;
        for (long c : buckets) total += c;
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * p);
        long seen = 0;
        for (int b = 0; b < buckets.length; b++) {
            seen += buckets[b];
            if (seen >= target) return (1L << (b + 1)) / 1000.0;
        }
        return (1L << buckets.length) / 1000.0;
    }
}
