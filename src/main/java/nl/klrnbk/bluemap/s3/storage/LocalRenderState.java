package nl.klrnbk.bluemap.s3.storage;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.bluemap.s3.KeyLayout;
import nl.klrnbk.bluemap.s3.client.S3Client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Render state (tile, chunk and region state) of one map, kept on local disk so that BlueMap's
 * synchronized state code never waits for the network. The layout follows BlueMap's FileMapStorage:
 * {@code <root>/rstate/<grid>.tiles.dat}, {@code .chunks.dat} and {@code rstate/regions/<grid>.regions.dat},
 * all GZIP.
 *
 * <p>On first access, state objects that an earlier S3 based storage left in the bucket are imported
 * once. The marker file is created only after a complete import, and every file is moved into place
 * atomically, so an interrupted import simply resumes (existing complete files are skipped).
 * All state access blocks until the import is done.
 */
public final class LocalRenderState {

    private static final String MARKER = ".s3-imported";
    private static final int IMPORT_THREADS = 8;

    private final S3Client client;
    private final String remotePrefix;
    private final Path root;
    private final Path marker;
    private final Path stateDir;
    private final Object importLock = new Object();
    private volatile boolean ready;

    private final GridStorage tiles;
    private final GridStorage chunks;
    private final GridStorage regions;

    public LocalRenderState(S3Client client, String remotePrefix, Path root) {
        this.client = client;
        this.remotePrefix = remotePrefix;
        this.root = root;
        this.marker = root.resolve(MARKER);
        this.stateDir = root.resolve("rstate");
        Path regionsDir = stateDir.resolve("regions");
        this.tiles = new Guarded(new LocalFileGrid(stateDir, ".tiles.dat", Compression.GZIP, regionsDir));
        this.chunks = new Guarded(new LocalFileGrid(stateDir, ".chunks.dat", Compression.GZIP, regionsDir));
        this.regions = new Guarded(new LocalFileGrid(regionsDir, ".regions.dat", Compression.GZIP, null));
    }

    public GridStorage tileState() {
        return tiles;
    }

    public GridStorage chunkState() {
        return chunks;
    }

    public GridStorage regionState() {
        return regions;
    }

    /** Blocks until the one-time import has finished. Cheap after the first successful call. */
    public void ensureReady() throws IOException {
        if (ready) return;
        synchronized (importLock) {
            if (ready) return;
            if (!Files.exists(marker)) importFromBucket();
            ready = true;
        }
    }

    /** Removes all local state of the map. The marker is kept so the deleted map is never re-imported. */
    public void deleteAll() throws IOException {
        synchronized (importLock) {
            if (Files.exists(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(p);
                }
            }
            Files.createDirectories(root);
            Files.createFile(marker);
            ready = true;
        }
    }

    private void importFromBucket() throws IOException {
        Logger.global.logInfo("S3 storage: importing render state from '" + remotePrefix + "' to " + root);
        Files.createDirectories(root);
        List<String> keys = new ArrayList<>();
        client.listAll(remotePrefix, key -> {
            if (targetOf(key) != null) keys.add(key);
        });

        ExecutorService pool = Executors.newFixedThreadPool(IMPORT_THREADS, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BlueMap-S3-Import-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        int imported = 0, skipped = 0;
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String key : keys) futures.add(pool.submit(() -> importOne(key)));
            for (Future<Boolean> f : futures) {
                if (f.get()) imported++; else skipped++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Render state import interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException("Render state import failed", cause);
        } finally {
            pool.shutdownNow();
        }
        Files.createFile(marker);
        Logger.global.logInfo("S3 storage: render state import done (" + imported + " imported, " + skipped
                + " already present). Render state is now local at " + root);
    }

    private boolean importOne(String key) throws IOException {
        Path target = targetOf(key);
        if (Files.exists(target)) return false;
        byte[] data = client.get(key);
        if (data == null) throw new IOException("Render state object disappeared during import: " + key);
        Files.createDirectories(target.getParent());
        Path part = Files.createTempFile(target.getParent(), ".import-", ".filepart");
        try {
            Files.write(part, data);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
        return true;
    }

    /** Local target of a remote state key, or null if the key is not a state object. */
    Path targetOf(String key) {
        if (!key.startsWith(remotePrefix)) return null;
        String relative = key.substring(remotePrefix.length());
        String suffix;
        if (relative.startsWith("regions/")) {
            suffix = ".regions.dat";
            if (KeyLayout.parseGridPath(relative.substring("regions/".length()), suffix) == null) return null;
        } else if (relative.endsWith(".tiles.dat")) {
            suffix = ".tiles.dat";
            if (KeyLayout.parseGridPath(relative, suffix) == null) return null;
        } else if (relative.endsWith(".chunks.dat")) {
            suffix = ".chunks.dat";
            if (KeyLayout.parseGridPath(relative, suffix) == null) return null;
        } else {
            return null;
        }
        return stateDir.resolve(relative);
    }

    /** Makes every access wait for the import. */
    private final class Guarded implements GridStorage {
        private final GridStorage delegate;

        Guarded(GridStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public OutputStream write(int x, int z) throws IOException {
            ensureReady();
            return delegate.write(x, z);
        }

        @Override
        public CompressedInputStream read(int x, int z) throws IOException {
            ensureReady();
            return delegate.read(x, z);
        }

        @Override
        public void delete(int x, int z) throws IOException {
            ensureReady();
            delegate.delete(x, z);
        }

        @Override
        public boolean exists(int x, int z) throws IOException {
            ensureReady();
            return delegate.exists(x, z);
        }

        @Override
        public ItemStorage cell(int x, int z) {
            return new GridStorageCell(this, x, z);
        }

        @Override
        public Stream<Cell> stream() throws IOException {
            ensureReady();
            return delegate.stream().<Cell>map(c -> new GridStorageCell(this, c.getX(), c.getZ()));
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
