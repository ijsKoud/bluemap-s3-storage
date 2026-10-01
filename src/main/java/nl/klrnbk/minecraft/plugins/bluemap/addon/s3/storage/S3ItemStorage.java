package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** One object in the bucket. Writing buffers in memory and hands the bytes to the {@link ObjectStore} on close. */
public class S3ItemStorage implements ItemStorage {

    private final S3Storage owner;
    private final String key;
    private final Compression compression;
    private final ObjectMeta meta;
    private final Runnable onWritten;

    public S3ItemStorage(S3Storage owner, String key, Compression compression, ObjectMeta meta, Runnable onWritten) {
        this.owner = owner;
        this.key = key;
        this.compression = compression;
        this.meta = meta;
        this.onWritten = onWritten;
    }

    public String key() {
        return key;
    }

    @Override
    public OutputStream write() throws IOException {
        owner.ensureOpen();
        return compression.compress(new CommitOnCloseStream());
    }

    @Override
    public CompressedInputStream read() throws IOException {
        owner.ensureOpen();
        byte[] data = owner.objects().get(key);
        if (data == null) return null;
        return new CompressedInputStream(new ByteArrayInputStream(data), compression);
    }

    @Override
    public void delete() throws IOException {
        owner.ensureOpen();
        owner.objects().delete(key);
    }

    @Override
    public boolean exists() throws IOException {
        owner.ensureOpen();
        return owner.objects().exists(key);
    }

    @Override
    public boolean isClosed() {
        return owner.isClosed();
    }

    /** Collects the (already compressed) bytes; close() commits them exactly once. */
    private final class CommitOnCloseStream extends ByteArrayOutputStream {
        private boolean committed;

        @Override
        public void close() throws IOException {
            if (committed) return;
            committed = true;
            owner.ensureOpen();
            owner.objects().put(key, toByteArray(), meta);
            if (onWritten != null) onWritten.run();
        }
    }
}
