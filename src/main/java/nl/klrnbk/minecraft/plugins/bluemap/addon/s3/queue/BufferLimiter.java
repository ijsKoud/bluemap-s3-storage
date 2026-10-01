package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounds the write-behind buffer by bytes and entries. Producers block in {@link #acquire} when the
 * buffer is full (backpressure); the time spent blocked is counted. A single op larger than the byte
 * limit is admitted when the buffer is empty, so it can never deadlock.
 */
final class BufferLimiter {

    private final long maxBytes;
    private final int maxEntries;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition space = lock.newCondition();
    private long bytes;
    private int entries;
    private boolean closed;
    private final LongAdder blockedNanos = new LongAdder();

    BufferLimiter(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    void acquire(long size) throws IOException {
        lock.lock();
        long waitStart = 0;
        boolean waited = false;
        try {
            while (!closed && !fits(size)) {
                if (!waited) {
                    waited = true;
                    waitStart = System.nanoTime();
                }
                space.await();
            }
            if (closed) throw new IOException("S3 storage is closed");
            bytes += size;
            entries++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for write buffer space");
        } finally {
            if (waited) blockedNanos.add(System.nanoTime() - waitStart);
            lock.unlock();
        }
    }

    void release(long size) {
        lock.lock();
        try {
            bytes -= size;
            entries--;
            space.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Wakes blocked producers with an exception; used when the store shuts down. */
    void close() {
        lock.lock();
        try {
            closed = true;
            space.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean fits(long size) {
        return entries < maxEntries && (bytes == 0 || bytes + size <= maxBytes);
    }

    long bytes() {
        lock.lock();
        try { return bytes; } finally { lock.unlock(); }
    }

    int entries() {
        lock.lock();
        try { return entries; } finally { lock.unlock(); }
    }

    long blockedNanos() {
        return blockedNanos.sum();
    }
}
