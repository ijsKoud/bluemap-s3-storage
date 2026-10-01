package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.MapStorage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.KeyLayout;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;

/** Storage of one map. Tiles and items go to S3, render state stays local. */
public final class S3MapStorage implements MapStorage {

    private final S3Storage owner;
    private final String mapId;
    private final KeyLayout layout;
    private final Compression compression;
    private final ObjectKinds kinds;
    private final LocalRenderState renderState;
    private final GridStorage hires;
    private final Map<Integer, GridStorage> lowres = new ConcurrentHashMap<>();

    S3MapStorage(S3Storage owner, String mapId, KeyLayout layout, Compression compression, ObjectKinds kinds,
                 LocalRenderState renderState) {
        this.owner = owner;
        this.mapId = mapId;
        this.layout = layout;
        this.compression = compression;
        this.kinds = kinds;
        this.renderState = renderState;
        this.hires = new S3GridStorage(owner, layout.hiresPrefix(mapId), layout.hiresSuffix(), compression, kinds.hires());
    }

    @Override
    public GridStorage hiresTiles() {
        return hires;
    }

    @Override
    public GridStorage lowresTiles(int lod) {
        return lowres.computeIfAbsent(lod, l -> new S3GridStorage(owner, layout.lowresPrefix(mapId, l),
                KeyLayout.lowresSuffix(), Compression.NONE, kinds.lowres()));
    }

    @Override
    public GridStorage tileState() {
        return renderState.tileState();
    }

    @Override
    public GridStorage chunkState() {
        return renderState.chunkState();
    }

    /** Not annotated with @Override: the method does not exist in BlueMap 5.3 but is required by newer versions. */
    public GridStorage regionState() {
        return renderState.regionState();
    }

    @Override
    public ItemStorage asset(String name) {
        return new S3ItemStorage(owner, layout.assetKey(mapId, name), Compression.NONE, kinds.asset(name), null);
    }

    @Override
    public ItemStorage settings() {
        // A new map becomes visible in mapIds() as soon as its settings are written.
        return new S3ItemStorage(owner, layout.settingsKey(mapId), Compression.NONE, kinds.json(), owner::invalidateMapIds, true);
    }

    @Override
    public ItemStorage textures() {
        return new S3ItemStorage(owner, layout.texturesKey(mapId), compression,
                kinds.textures(compression != Compression.NONE), null);
    }

    @Override
    public ItemStorage markers() {
        return new S3ItemStorage(owner, layout.markersKey(mapId), Compression.NONE, kinds.json(), null, true);
    }

    @Override
    public ItemStorage players() {
        return new S3ItemStorage(owner, layout.playersKey(mapId), Compression.NONE, kinds.json(), null, true);
    }

    @Override
    public void delete(DoublePredicate onProgress) throws IOException {
        owner.ensureOpen();
        // Remote objects first (cancellable); local state only after everything remote is gone.
        boolean completed = owner.objects().deletePrefix(layout.mapPrefix(mapId), onProgress);
        owner.invalidateMapIds();
        owner.forgetWrites(layout.mapPrefix(mapId));
        if (completed) renderState.deleteAll();
    }

    @Override
    public boolean exists() throws IOException {
        owner.ensureOpen();
        try (Stream<String> keys = owner.objects().listKeys(layout.mapPrefix(mapId))) {
            return keys.findAny().isPresent();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public boolean isClosed() {
        return owner.isClosed();
    }
}
