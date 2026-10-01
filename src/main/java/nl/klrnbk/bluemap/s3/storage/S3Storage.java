package nl.klrnbk.bluemap.s3.storage;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.Storage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.bluemap.s3.KeyLayout;
import nl.klrnbk.bluemap.s3.client.S3Client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** BlueMap {@link Storage} backed by an S3 bucket. */
public final class S3Storage implements Storage {

    private static final Pattern MAP_ID = Pattern.compile("[\\w.\\-]+");

    private final S3Client client;
    private final ObjectStore objects;
    private final KeyLayout layout;
    private final Compression compression;
    private final ObjectKinds kinds;
    private final Path renderStateRoot;
    private final long listCacheTtlNanos;
    private final Map<String, S3MapStorage> maps = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private record MapIdSnapshot(List<String> ids, long takenNanos) {}
    private volatile MapIdSnapshot mapIdCache;

    private final AutoCloseable extra;

    public S3Storage(S3Client client, ObjectStore objects, KeyLayout layout, Compression compression,
                     ObjectKinds kinds, Path renderStateRoot, int listCacheTtlSeconds) {
        this(client, objects, layout, compression, kinds, renderStateRoot, listCacheTtlSeconds, null);
    }

    /** @param extra closed after the pending writes were flushed and before the HTTP client closes (e.g. the metrics reporter) */
    public S3Storage(S3Client client, ObjectStore objects, KeyLayout layout, Compression compression,
                     ObjectKinds kinds, Path renderStateRoot, int listCacheTtlSeconds, AutoCloseable extra) {
        this.extra = extra;
        this.client = client;
        this.objects = objects;
        this.layout = layout;
        this.compression = compression;
        this.kinds = kinds;
        this.renderStateRoot = renderStateRoot;
        this.listCacheTtlNanos = listCacheTtlSeconds * 1_000_000_000L;
    }

    ObjectStore objects() {
        return objects;
    }

    void ensureOpen() throws IOException {
        if (closed) throw new IOException("S3 storage is closed");
    }

    void invalidateMapIds() {
        mapIdCache = null;
    }

    @Override
    public void initialize() throws IOException {
        ensureOpen();
        // Fails early and clearly on a wrong endpoint, bucket or credentials.
        client.list(layout.rootPrefix(), "/", null, 1);
        java.nio.file.Files.createDirectories(renderStateRoot);
    }

    @Override
    public S3MapStorage map(String mapId) {
        if (!MAP_ID.matcher(mapId).matches() || mapId.equals(".") || mapId.equals(".."))
            throw new IllegalArgumentException("Invalid map id: " + mapId);
        return maps.computeIfAbsent(mapId, id -> new S3MapStorage(this, id, layout, compression, kinds,
                new LocalRenderState(client, layout.renderStatePrefix(id), renderStateRoot.resolve(id))));
    }

    @Override
    public Stream<String> mapIds() throws IOException {
        ensureOpen();
        MapIdSnapshot cached = mapIdCache;
        long now = System.nanoTime();
        if (cached != null && listCacheTtlNanos > 0 && now - cached.takenNanos() < listCacheTtlNanos)
            return cached.ids().stream();

        List<String> ids = new ArrayList<>();
        for (String prefix : objects.listPrefixes(layout.rootPrefix())) {
            String id = prefix.substring(layout.rootPrefix().length(), prefix.length() - 1);
            if (!MAP_ID.matcher(id).matches()) continue;
            if (objects.exists(layout.settingsKey(id))) ids.add(id);
        }
        mapIdCache = new MapIdSnapshot(List.copyOf(ids), now);
        return ids.stream();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            objects.close();
        } finally {
            try {
                if (extra != null) extra.close();
            } catch (Exception e) {
                Logger.global.logWarning("S3 storage: closing the metrics reporter failed: " + e);
            } finally {
                client.close();
            }
        }
        Logger.global.logInfo("S3 storage closed");
    }
}
