package nl.klrnbk.bluemap.s3.storage;

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.bluemap.s3.KeyLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.util.stream.Stream;

/** A grid of tiles under one key prefix, e.g. {@code <map>/tiles/0/}. */
public class S3GridStorage implements GridStorage {

    private final S3Storage owner;
    private final String prefix;
    private final String suffix;
    private final Compression compression;
    private final ObjectMeta meta;

    public S3GridStorage(S3Storage owner, String prefix, String suffix, Compression compression, ObjectMeta meta) {
        this.owner = owner;
        this.prefix = prefix;
        this.suffix = suffix;
        this.compression = compression;
        this.meta = meta;
    }

    String keyOf(int x, int z) {
        return prefix + KeyLayout.gridPath(x, z) + suffix;
    }

    @Override
    public OutputStream write(int x, int z) throws IOException {
        return cell(x, z).write();
    }

    @Override
    public CompressedInputStream read(int x, int z) throws IOException {
        return cell(x, z).read();
    }

    @Override
    public void delete(int x, int z) throws IOException {
        cell(x, z).delete();
    }

    @Override
    public boolean exists(int x, int z) throws IOException {
        return cell(x, z).exists();
    }

    @Override
    public ItemStorage cell(int x, int z) {
        return new GridCell(x, z);
    }

    @Override
    public Stream<Cell> stream() throws IOException {
        owner.ensureOpen();
        Stream<String> keys = owner.objects().listKeys(prefix);
        // Listing pages are fetched lazily; a failing page surfaces as UncheckedIOException, like BlueMap's own file walk.
        return keys.<Cell>map(key -> {
                    int[] pos = KeyLayout.parseGridPath(key.substring(prefix.length()), suffix);
                    return pos == null ? null : new GridCell(pos[0], pos[1]);
                })
                .filter(java.util.Objects::nonNull)
                .onClose(keys::close);
    }

    @Override
    public boolean isClosed() {
        return owner.isClosed();
    }

    private final class GridCell extends S3ItemStorage implements Cell {
        private final int x, z;

        GridCell(int x, int z) {
            super(owner, keyOf(x, z), compression, meta, null);
            this.x = x;
            this.z = z;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getZ() {
            return z;
        }
    }
}
