package nl.klrnbk.bluemap.s3.queue;

import nl.klrnbk.bluemap.s3.log.AddonLog;
import nl.klrnbk.bluemap.s3.client.S3Client;
import nl.klrnbk.bluemap.s3.storage.DirectObjectStore;
import nl.klrnbk.bluemap.s3.storage.ObjectMeta;
import nl.klrnbk.bluemap.s3.storage.ObjectStore;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Write-behind {@link ObjectStore}: writes and deletes return as soon as the bytes are in memory
 * (and in the local spool), and are uploaded by background workers.
 *
 * <h3>Concurrency model</h3>
 * All per-key state lives in a {@link KeyState} that is only ever mutated inside
 * {@code states.compute(key, ...)}, which gives per-key mutual exclusion without any extra locks.
 * Those lambdas never block and never do I/O. Invariants, for every key:
 * <ul>
 *   <li>{@code latest} is the newest acknowledged op; reads are served from it (read-your-writes).</li>
 *   <li>{@code uploading} is the op a worker is currently sending, or null. At most one per key, so
 *       two requests for the same key are never in flight together and never reorder.</li>
 *   <li>A key is in {@code workQueue} at most once ({@code queued}), and only when {@code uploading}
 *       is null. A newer write that arrives while an upload runs does not queue the key; the
 *       completion of the running upload does. This is the per-key chain.</li>
 *   <li>If {@code latest != uploading} the latest op has not started, so a newer write replaces it
 *       (coalescing). An op that already started is never replaced, only followed.</li>
 * </ul>
 * Completion compares ops by reference ({@code st.latest == op}) so a newer write is never dropped.
 */
public final class WriteBehindObjectStore implements ObjectStore {

    private static final class KeyState {
        volatile PendingOp latest;
        PendingOp uploading;   // guarded by compute()
        boolean queued;        // guarded by compute()
    }

    private final S3Client client;
    private final DirectObjectStore direct;
    private final WriteBehindConfig config;
    private final BufferLimiter limiter;
    private final Spool spool; // null when disabled
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> workQueue = new LinkedBlockingQueue<>();
    private final Set<String> failedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong(1);
    private final AtomicInteger activeEnqueues = new AtomicInteger();
    private final AtomicInteger uploadsInFlight = new AtomicInteger();
    /** Failed ops currently being retried; capped so persistently failing keys cannot occupy all upload threads. */
    private final AtomicInteger failedRetriesInFlight = new AtomicInteger();
    private final LongAdder uploadsCompleted = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    private final LongAdder failedUploads = new LongAdder();
    private final RateLimitedLog failureLog = new RateLimitedLog(10_000);
    private final RateLimitedLog spoolLog = new RateLimitedLog(10_000);
    private final List<Thread> workers = new ArrayList<>();
    private final ScheduledExecutorService maintenance;
    private volatile boolean accepting = true;
    private volatile boolean stopping;
    private volatile boolean closed;

    public WriteBehindObjectStore(S3Client client, WriteBehindConfig config) throws IOException {
        this.client = client;
        this.direct = new DirectObjectStore(client);
        this.config = config;
        this.limiter = new BufferLimiter(config.bufferMaxBytes(), config.bufferMaxEntries());
        this.spool = config.spoolEnabled() ? new Spool(config.spoolPath(), config.spoolMaxBytes()) : null;

        for (int i = 1; i <= config.uploadThreads(); i++) {
            Thread t = new Thread(this::workerLoop, "BlueMap-S3-Upload-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        this.maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlueMap-S3-Spool");
            t.setDaemon(true);
            return t;
        });
        long tickMillis = Math.max(10, config.failedRetryInterval().toMillis() / 4);
        maintenance.scheduleWithFixedDelay(this::retryFailed, tickMillis, tickMillis, TimeUnit.MILLISECONDS);

        if (spool != null) replaySpool(); // workers are already running, so a large spool can drain through the buffer
    }

    // ---- ObjectStore: writes ----

    @Override
    public void put(String key, byte[] data, ObjectMeta meta) throws IOException {
        enqueue(new PendingOp(key, PendingOp.Type.PUT, data, meta, sequence.getAndIncrement()), false);
    }

    @Override
    public void delete(String key) throws IOException {
        enqueue(new PendingOp(key, PendingOp.Type.DELETE, null, null, sequence.getAndIncrement()), false);
    }

    private void enqueue(PendingOp op, boolean alreadySpooled) throws IOException {
        activeEnqueues.incrementAndGet();
        try {
            if (!accepting) throw new IOException("S3 storage is closed");
            limiter.acquire(op.size); // backpressure: blocks only when the buffer is full
            op.accounted = true;
            if (spool != null && !alreadySpooled) {
                try {
                    op.spoolFile = spool.write(op);
                    if (op.spoolFile == null)
                        spoolLog.warn("S3 spool is full (" + config.spoolMaxBytes() + " bytes), keeping writes memory-only until it drains");
                } catch (IOException e) {
                    spoolLog.warn("S3 spool write failed, keeping this write memory-only: " + e);
                }
            }
            failedKeys.remove(op.key);
            PendingOp[] replaced = new PendingOp[1];
            states.compute(op.key, (k, st) -> {
                if (st == null) st = new KeyState();
                PendingOp prev = st.latest;
                if (prev != null && prev != st.uploading) replaced[0] = prev; // not started yet: coalesce
                st.latest = op;
                if (st.uploading == null && !st.queued) {
                    st.queued = true;
                    workQueue.add(k);
                }
                return st;
            });
            if (replaced[0] != null) {
                coalesced.increment();
                discard(replaced[0]);
            }
        } finally {
            activeEnqueues.decrementAndGet();
        }
    }

    /** Releases buffer accounting and the spool file of an op that will never be uploaded, or was. */
    private void discard(PendingOp op) {
        endRetry(op);
        if (op.accounted) {
            op.accounted = false;
            limiter.release(op.size);
        }
        if (spool != null) spool.delete(op);
    }

    private void replaySpool() throws IOException {
        List<PendingOp> ops = spool.loadAll();
        if (ops.isEmpty()) return;
        AddonLog.info("S3 spool: replaying " + ops.size() + " pending operations from the previous run");
        long maxSeq = 0;
        for (PendingOp op : ops) maxSeq = Math.max(maxSeq, op.seq);
        sequence.set(maxSeq + 1);
        for (PendingOp op : ops) enqueue(op, true); // older ops for the same key get coalesced away, files removed
    }

    // ---- workers ----

    private void workerLoop() {
        while (!stopping) {
            String key;
            try {
                key = workQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (stopping) return;
                continue;
            }
            if (key == null) continue;
            PendingOp op = begin(key);
            if (op == null) continue;
            uploadsInFlight.incrementAndGet();
            boolean ok = false;
            Throwable error = null;
            try {
                upload(op);
                ok = true;
            } catch (InterruptedIOException e) {
                error = e; // shutdown; the op stays pending (and spooled)
            } catch (IOException | RuntimeException e) {
                error = e;
            } finally {
                uploadsInFlight.decrementAndGet();
            }
            complete(op, ok, error);
        }
    }

    /** Picks the latest op of the key for upload, or null if there is nothing to do. */
    private PendingOp begin(String key) {
        PendingOp[] out = new PendingOp[1];
        states.compute(key, (k, st) -> {
            if (st == null) return null;
            st.queued = false;
            PendingOp latest = st.latest;
            if (latest != null && st.uploading == null) {
                st.uploading = latest;
                out[0] = latest;
            }
            return st.latest == null && st.uploading == null ? null : st;
        });
        return out[0];
    }

    private void upload(PendingOp op) throws IOException {
        if (op.type == PendingOp.Type.DELETE) {
            client.delete(op.key);
            return;
        }
        byte[] data = op.data;
        if (data == null) data = spool.readData(op); // evicted to the spool after an earlier failure
        client.put(op.key, data, op.meta.contentType(), op.meta.cacheControl());
    }

    private void complete(PendingOp op, boolean ok, Throwable error) {
        boolean[] stillLatest = new boolean[1];
        states.compute(op.key, (k, st) -> {
            if (st == null) return null;
            st.uploading = null;
            stillLatest[0] = st.latest == op;
            if (ok && stillLatest[0]) st.latest = null;
            // A newer op arrived while this one was uploading: now it may start (per-key chain).
            if (st.latest != null && st.latest != op && !st.queued) {
                st.queued = true;
                workQueue.add(k);
            }
            return st.latest == null && !st.queued ? null : st;
        });
        if (ok) {
            uploadsCompleted.increment();
            if (stillLatest[0]) failedKeys.remove(op.key);
            discard(op);
        } else if (!stillLatest[0]) {
            discard(op); // superseded by a newer op, which carries the state that matters
        } else if (!stopping) {
            endRetry(op);
            op.failures++;
            op.nextRetryNanos = System.nanoTime() + retryBackoffNanos(op.failures);
            failedUploads.increment();
            failedKeys.add(op.key);
            failureLog.warn("S3 upload failed for '" + op.key + "': " + describe(error) + "; kept "
                    + (op.spoolFile != null ? "in the spool" : "in memory") + ", will retry");
            if (op.spoolFile != null && op.type == PendingOp.Type.PUT) evict(op);
        }
    }

    /** Drops the in-memory bytes of a failed op whose copy is safe in the spool, freeing buffer space. */
    private void evict(PendingOp op) {
        op.data = null;
        if (op.accounted) {
            op.accounted = false;
            limiter.release(op.size);
        }
    }

    private void endRetry(PendingOp op) {
        if (op.retryScheduled.compareAndSet(true, false)) failedRetriesInFlight.decrementAndGet();
    }

    /** The n-th retry of a failed op waits interval * 2^(n-1), at most 20 intervals (10 minutes by default). */
    private long retryBackoffNanos(int failures) {
        long interval = config.failedRetryInterval().toNanos();
        return Math.min(interval << Math.min(failures - 1, 6), interval * 20);
    }

    /**
     * Re-queues failed ops whose backoff has elapsed, but never more than a small share of the upload threads
     * at once. A key that hangs holds a thread for all its attempts; without this cap a few bad keys retried
     * every interval would occupy the whole pool and starve healthy uploads.
     */
    private void retryFailed() {
        try {
            int cap = Math.max(1, config.uploadThreads() / 8);
            long now = System.nanoTime();
            for (String key : failedKeys) {
                if (failedRetriesInFlight.get() >= cap) return;
                states.computeIfPresent(key, (k, st) -> {
                    PendingOp latest = st.latest;
                    if (latest != null && st.uploading == null && !st.queued && now - latest.nextRetryNanos >= 0
                            && latest.retryScheduled.compareAndSet(false, true)) {
                        failedRetriesInFlight.incrementAndGet();
                        st.queued = true;
                        workQueue.add(k);
                    }
                    return st;
                });
            }
        } catch (RuntimeException e) {
            AddonLog.warn("S3 retry of failed uploads crashed: " + e);
        }
    }

    private static String describe(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    // ---- ObjectStore: reads ----

    @Override
    public byte[] get(String key) throws IOException {
        KeyState st = states.get(key);
        PendingOp latest = st == null ? null : st.latest;
        if (latest != null) {
            if (latest.type == PendingOp.Type.DELETE) return null;
            byte[] data = latest.data;
            if (data != null) return data;
            try {
                return spool.readData(latest);
            } catch (IOException e) {
                // spool file just removed because the op completed; S3 has it now
            }
        }
        return client.get(key);
    }

    @Override
    public boolean exists(String key) throws IOException {
        KeyState st = states.get(key);
        PendingOp latest = st == null ? null : st.latest;
        if (latest != null) return latest.type == PendingOp.Type.PUT;
        return client.head(key);
    }

    @Override
    public Stream<String> listKeys(String prefix) throws IOException {
        Set<String> deleted = new HashSet<>();
        Set<String> puts = new LinkedHashSet<>();
        for (var e : states.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            PendingOp latest = e.getValue().latest;
            if (latest == null) continue;
            if (latest.type == PendingOp.Type.DELETE) deleted.add(e.getKey());
            else puts.add(e.getKey());
        }
        Stream<String> remote = direct.listKeys(prefix);
        Iterator<String> remoteIt = remote.iterator();
        Iterator<String> mergedIt = new Iterator<>() {
            String next;
            Iterator<String> extra;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (extra == null) {
                        if (remoteIt.hasNext()) {
                            String k = remoteIt.next();
                            if (deleted.contains(k)) continue;
                            puts.remove(k); // already listed by S3, emit once
                            next = k;
                        } else {
                            extra = puts.iterator();
                        }
                    } else if (extra.hasNext()) {
                        next = extra.next();
                    } else {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                String k = next;
                next = null;
                return k;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(mergedIt, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(remote::close);
    }

    @Override
    public List<String> listPrefixes(String prefix) throws IOException {
        TreeSet<String> result = new TreeSet<>(direct.listPrefixes(prefix));
        for (var e : states.entrySet()) {
            String key = e.getKey();
            PendingOp latest = e.getValue().latest;
            if (latest == null || latest.type != PendingOp.Type.PUT || !key.startsWith(prefix)) continue;
            int slash = key.indexOf('/', prefix.length());
            if (slash >= 0) result.add(key.substring(0, slash + 1));
        }
        return new ArrayList<>(result);
    }

    @Override
    public boolean deletePrefix(String prefix, DoublePredicate onProgress) throws IOException {
        // 1. Drop every op under the prefix that has not started, so nothing is re-created afterwards.
        List<PendingOp> dropped = new ArrayList<>();
        for (String key : new ArrayList<>(states.keySet())) {
            if (!key.startsWith(prefix)) continue;
            states.compute(key, (k, st) -> {
                if (st == null) return null;
                PendingOp latest = st.latest;
                if (latest != null && latest != st.uploading) {
                    dropped.add(latest);
                    st.latest = st.uploading; // null if nothing is uploading
                }
                return st.latest == null && st.uploading == null && !st.queued ? null : st;
            });
            failedKeys.remove(key);
        }
        dropped.forEach(this::discard);
        // 2. Let uploads that already started finish, otherwise they could land after the listing below.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline && anyUploading(prefix)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for uploads");
            }
        }
        // 3. Delete what is in the bucket.
        return direct.deletePrefix(prefix, onProgress);
    }

    private boolean anyUploading(String prefix) {
        for (var e : states.entrySet())
            if (e.getKey().startsWith(prefix) && e.getValue().uploading != null) return true;
        return false;
    }

    // ---- lifecycle and stats ----

    public WriteBehindStats stats() {
        return new WriteBehindStats(limiter.entries(), limiter.bytes(), uploadsInFlight.get(), uploadsCompleted.sum(),
                coalesced.sum(), failedUploads.sum(), failedKeys.size(), spool == null ? 0 : spool.bytes(),
                limiter.blockedNanos());
    }

    /** Number of keys that still have an op that is not safely in the bucket. */
    public int pendingKeys() {
        int n = 0;
        for (KeyState st : states.values()) if (st.latest != null) n++;
        return n;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        accepting = false;
        limiter.close(); // wakes producers blocked on a full buffer; they get an IOException

        long deadline = System.nanoTime() + config.shutdownFlushTimeout().toNanos();
        while (activeEnqueues.get() > 0 && System.nanoTime() < deadline) sleepQuietly(5);

        AddonLog.info("S3 storage: flushing " + pendingKeys() + " pending operations (timeout "
                + config.shutdownFlushTimeout().toSeconds() + "s)");
        while (System.nanoTime() < deadline && pendingKeys() > 0) {
            if (onlyFailedLeft()) break; // nothing can make progress, do not wait the full timeout
            sleepQuietly(20);
        }

        stopping = true;
        maintenance.shutdownNow();
        for (Thread w : workers) w.interrupt();
        for (Thread w : workers) {
            try {
                w.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int left = 0, memoryOnly = 0;
        for (KeyState st : states.values()) {
            PendingOp latest = st.latest;
            if (latest == null) continue;
            left++;
            if (latest.spoolFile == null) memoryOnly++;
        }
        if (left == 0) {
            AddonLog.info("S3 storage: all pending operations flushed");
        } else {
            AddonLog.warn("S3 storage: " + left + " operations were left unflushed at shutdown; "
                    + (left - memoryOnly) + " remain in the spool and will be uploaded on the next start"
                    + (memoryOnly > 0 ? ", " + memoryOnly + " were memory-only and are LOST" : ""));
        }
    }

    private boolean onlyFailedLeft() {
        if (!workQueue.isEmpty() || uploadsInFlight.get() > 0) return false;
        for (var e : states.entrySet()) {
            if (e.getValue().latest != null && !failedKeys.contains(e.getKey())) return false;
        }
        return true;
    }

    /** Test hook: stops like a crash, without flushing, leaving the spool as it is. */
    void abandonForTest() {
        accepting = false;
        stopping = true;
        closed = true;
        limiter.close();
        maintenance.shutdownNow();
        for (Thread w : workers) w.interrupt();
        for (Thread w : workers) {
            try { w.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
