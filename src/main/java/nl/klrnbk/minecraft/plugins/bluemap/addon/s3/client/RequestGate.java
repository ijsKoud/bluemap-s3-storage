package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Limits concurrency and request rate for every HTTP attempt.
 *
 * <p>Concurrency: {@code total} holds all in-flight slots. Writes additionally need a permit from
 * {@code writeSlots}, which is smaller by a reserved share, so uploads can never occupy every slot
 * and reads always find room.
 *
 * <p>Rate: requests get strictly spaced start times (1/rps apart). A caller reserves the next free
 * start time under a lock and sleeps outside of it. No burst credit is kept, so any window of T
 * seconds sees at most {@code T * rps + 1} request starts.
 */
public final class RequestGate {

    public enum Kind { READ, WRITE }

    private final int maxInFlight;
    private final Semaphore total;
    private final Semaphore writeSlots;
    private final long intervalNanos;
    private long nextStartNanos = System.nanoTime();

    public RequestGate(int maxInFlight, int maxRequestsPerSecond) {
        this.maxInFlight = maxInFlight;
        int reserve = Math.max(1, maxInFlight / 8);
        this.total = new Semaphore(maxInFlight, true);
        this.writeSlots = new Semaphore(Math.max(1, maxInFlight - reserve), true);
        this.intervalNanos = Math.max(1, 1_000_000_000L / maxRequestsPerSecond);
    }

    /** Blocks until a slot and a rate token are available. Must be paired with {@link #release}. */
    public void acquire(Kind kind) throws InterruptedException {
        if (kind == Kind.WRITE) writeSlots.acquire();
        try {
            total.acquire();
        } catch (InterruptedException e) {
            if (kind == Kind.WRITE) writeSlots.release();
            throw e;
        }
        try {
            long wait = reserveStart();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
        } catch (InterruptedException e) {
            release(kind);
            throw e;
        }
    }

    public void release(Kind kind) {
        total.release();
        if (kind == Kind.WRITE) writeSlots.release();
    }

    public int inFlight() {
        return maxInFlight - total.availablePermits();
    }

    private synchronized long reserveStart() {
        long now = System.nanoTime();
        if (nextStartNanos < now) nextStartNanos = now;
        long wait = nextStartNanos - now;
        nextStartNanos += intervalNanos;
        return wait;
    }
}
