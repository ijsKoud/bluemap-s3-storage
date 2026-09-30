package nl.klrnbk.bluemap.s3.client;

import java.util.concurrent.atomic.LongAdder;

/** Lock-free request counters and latency histograms (log2 buckets of microseconds). */
public final class S3Metrics {

    public enum Op { GET, HEAD, PUT, DELETE, LIST, DELETE_BATCH }

    private static final int BUCKETS = 40;

    private final LongAdder[][] histogram = new LongAdder[Op.values().length][BUCKETS];
    private final LongAdder[] count = new LongAdder[Op.values().length];
    private final LongAdder[] failures = new LongAdder[Op.values().length];
    private final LongAdder retries = new LongAdder();

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
        long total = count(op);
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * p);
        long seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            seen += histogram[op.ordinal()][b].sum();
            if (seen >= target) return (1L << (b + 1)) / 1000.0;
        }
        return (1L << BUCKETS) / 1000.0;
    }
}
