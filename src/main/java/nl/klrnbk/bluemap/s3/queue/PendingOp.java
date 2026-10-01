package nl.klrnbk.bluemap.s3.queue;

import nl.klrnbk.bluemap.s3.storage.ObjectMeta;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** One not yet completed write or delete of a key. Identity matters: completion compares by reference. */
final class PendingOp {

    enum Type { PUT, DELETE }

    final String key;
    final Type type;
    final ObjectMeta meta;
    final long seq;
    /** Bytes counted against the write buffer. */
    final long size;

    /** Null for deletes, and for puts whose bytes were evicted to the spool after an upload failure. */
    volatile byte[] data;
    /** Spool file holding this op, or null if it is memory-only. */
    volatile Path spoolFile;
    volatile long spoolSize;
    /** Number of times this op exhausted its retries; drives the backoff of the periodic retry. */
    volatile int failures;
    volatile long nextRetryNanos;
    /** True while this op counts against the limit of concurrently retried failed ops. */
    final AtomicBoolean retryScheduled = new AtomicBoolean();
    /** True while this op holds write buffer accounting. */
    volatile boolean accounted;

    PendingOp(String key, Type type, byte[] data, ObjectMeta meta, long seq) {
        this.key = key;
        this.type = type;
        this.data = data;
        this.meta = meta;
        this.seq = seq;
        this.size = type == Type.PUT ? data.length : key.length();
    }
}
