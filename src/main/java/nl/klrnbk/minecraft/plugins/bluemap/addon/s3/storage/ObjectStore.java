package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import java.io.IOException;
import java.util.List;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;

/**
 * Key/value view of the bucket used by all storage classes. Phase 3 has a synchronous
 * implementation; the write-behind implementation replaces it without touching the callers.
 */
public interface ObjectStore extends AutoCloseable {

    void put(String key, byte[] data, ObjectMeta meta) throws IOException;

    /** Returns the object body or null if it does not exist. */
    byte[] get(String key) throws IOException;

    boolean exists(String key) throws IOException;

    void delete(String key) throws IOException;

    /** Lazily lists all keys under the prefix. The stream must be closed by the caller. */
    Stream<String> listKeys(String prefix) throws IOException;

    /** Lists the immediate "directories" under the prefix (delimiter '/'), each ending in '/'. */
    List<String> listPrefixes(String prefix) throws IOException;

    /**
     * Deletes every object under the prefix in batches, also discarding pending writes.
     *
     * @param onProgress called with 0..1; returning false cancels
     * @return true if everything was deleted, false if cancelled
     */
    boolean deletePrefix(String prefix, DoublePredicate onProgress) throws IOException;

    @Override
    void close() throws IOException;
}
