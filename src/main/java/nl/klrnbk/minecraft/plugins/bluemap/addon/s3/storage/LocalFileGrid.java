package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.KeyLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A grid of files on local disk with the same path layout as BlueMap's FileGridStorage
 * ({@code <root>/x1/2/z3<suffix>}), written atomically via a temporary file and a move.
 * We carry our own copy because FileGridStorage is not public in every BlueMap version.
 */
public final class LocalFileGrid implements GridStorage {

    private static final AtomicLong TMP_COUNTER = new AtomicLong();

    private final Path root;
    private final String suffix;
    private final Compression compression;
    private final Path skipDir;

    /** @param skipDir a sub directory of root that belongs to another grid and is not listed, or null */
    public LocalFileGrid(Path root, String suffix, Compression compression, Path skipDir) {
        this.root = root;
        this.suffix = suffix;
        this.compression = compression;
        this.skipDir = skipDir;
    }

    Path pathOf(int x, int z) {
        return root.resolve(KeyLayout.gridPath(x, z) + suffix);
    }

    @Override
    public OutputStream write(int x, int z) throws IOException {
        Path target = pathOf(x, z);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + "." + TMP_COUNTER.incrementAndGet() + ".filepart");
        OutputStream file = Files.newOutputStream(part);
        OutputStream out = new java.io.FilterOutputStream(file) {
            private boolean closed;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    super.close();
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(part);
                }
            }
        };
        return compression.compress(out);
    }

    @Override
    public CompressedInputStream read(int x, int z) throws IOException {
        try {
            return new CompressedInputStream(Files.newInputStream(pathOf(x, z)), compression);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    @Override
    public void delete(int x, int z) throws IOException {
        Files.deleteIfExists(pathOf(x, z));
    }

    @Override
    public boolean exists(int x, int z) {
        return Files.exists(pathOf(x, z));
    }

    @Override
    public ItemStorage cell(int x, int z) {
        return new GridStorageCell(this, x, z);
    }

    @Override
    public Stream<Cell> stream() throws IOException {
        if (!Files.isDirectory(root)) return Stream.empty();
        Stream<Path> walk = Files.walk(root);
        return walk
                .filter(p -> skipDir == null || !p.startsWith(skipDir))
                .filter(Files::isRegularFile)
                .<Cell>map(p -> {
                    int[] pos = KeyLayout.parseGridPath(root.relativize(p).toString().replace('\\', '/'), suffix);
                    return pos == null ? null : new GridStorageCell(this, pos[0], pos[1]);
                })
                .filter(Objects::nonNull)
                .onClose(walk::close);
    }

    @Override
    public boolean isClosed() {
        return false;
    }
}
