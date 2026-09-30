package nl.klrnbk.bluemap.s3.queue;

import de.bluecolored.bluemap.core.logger.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Logs a warning at most once per interval; the suppressed count is appended to the next line. */
final class RateLimitedLog {

    private final long intervalNanos;
    private final AtomicLong lastNanos = new AtomicLong(System.nanoTime() - Long.MAX_VALUE / 2);
    private final AtomicInteger suppressed = new AtomicInteger();

    RateLimitedLog(long intervalMillis) {
        this.intervalNanos = intervalMillis * 1_000_000L;
    }

    void warn(String message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (now - last >= intervalNanos && lastNanos.compareAndSet(last, now)) {
            int skipped = suppressed.getAndSet(0);
            Logger.global.logWarning(skipped == 0 ? message : message + " (" + skipped + " similar messages suppressed)");
        } else {
            suppressed.incrementAndGet();
        }
    }
}
