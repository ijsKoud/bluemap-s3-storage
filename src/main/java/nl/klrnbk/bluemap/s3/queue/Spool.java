package nl.klrnbk.bluemap.s3.queue;

import de.bluecolored.bluemap.core.logger.Logger;
import nl.klrnbk.bluemap.s3.storage.ObjectMeta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Local disk copy of acknowledged but not yet uploaded ops, so a crash or kill loses nothing.
 *
 * <p>One file per op, named by sequence number so replay order is the write order. A file is written
 * as {@code <name>.tmp} and atomically renamed before the producer is acknowledged, so a reader never
 * sees a partial file. There is no fsync: the data survives a process crash or kill -9, but the most
 * recent ops can be lost on power failure.
 *
 * <p>File format: magic, version, seq, type, key, content type, cache control, data length, data, CRC32.
 */
final class Spool {

    private static final int MAGIC = 0x42533353; // "BS3S"
    private static final byte VERSION = 1;

    private final Path dir;
    private final long maxBytes;
    private final AtomicLong bytes = new AtomicLong();

    Spool(Path dir, long maxBytes) throws IOException {
        this.dir = dir;
        this.maxBytes = maxBytes;
        Files.createDirectories(dir);
    }

    long bytes() {
        return bytes.get();
    }

    /** Writes the op to disk. Returns null (memory-only) when the spool budget would be exceeded. */
    Path write(PendingOp op) throws IOException {
        byte[] key = op.key.getBytes(StandardCharsets.UTF_8);
        byte[] contentType = utf8(op.meta == null ? null : op.meta.contentType());
        byte[] cacheControl = utf8(op.meta == null ? null : op.meta.cacheControl());
        byte[] data = op.data == null ? new byte[0] : op.data;
        int headerSize = 4 + 1 + 8 + 1 + 4 + key.length + 4 + contentType.length + 4 + cacheControl.length + 4;
        long total = headerSize + (long) data.length + 8;
        if (bytes.get() + total > maxBytes) return null;

        ByteBuffer header = ByteBuffer.allocate(headerSize);
        header.putInt(MAGIC).put(VERSION).putLong(op.seq).put((byte) op.type.ordinal());
        header.putInt(key.length).put(key);
        header.putInt(contentType.length).put(contentType);
        header.putInt(cacheControl.length).put(cacheControl);
        header.putInt(data.length);
        header.flip();
        CRC32 crc = new CRC32();
        crc.update(header.duplicate());
        crc.update(data);
        ByteBuffer trailer = ByteBuffer.allocate(8).putLong(crc.getValue());
        trailer.flip();

        String name = String.format("%020d.op", op.seq);
        Path tmp = dir.resolve(name + ".tmp");
        Path file = dir.resolve(name);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer[] parts = {header, ByteBuffer.wrap(data), trailer};
            long remaining = total;
            while (remaining > 0) remaining -= ch.write(parts);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        op.spoolSize = total;
        bytes.addAndGet(total);
        return file;
    }

    void delete(PendingOp op) {
        Path file = op.spoolFile;
        if (file == null) return;
        op.spoolFile = null;
        try {
            if (Files.deleteIfExists(file)) bytes.addAndGet(-op.spoolSize);
        } catch (IOException e) {
            Logger.global.logWarning("S3 spool: could not delete " + file.getFileName() + ": " + e);
        }
    }

    /** Loads the data of a spooled put (used after the in-memory copy was evicted). */
    byte[] readData(PendingOp op) throws IOException {
        Path file = op.spoolFile;
        if (file == null) throw new NoSuchFileException("op is not spooled");
        return parse(file).data();
    }

    /** Reads all intact spool files in sequence order. Broken files are reported and removed. */
    List<PendingOp> loadAll() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<PendingOp> ops = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".tmp")) { // never acknowledged, so it is safe to drop
                Files.deleteIfExists(file);
                continue;
            }
            if (!name.endsWith(".op")) continue;
            try {
                Parsed p = parse(file);
                PendingOp op = new PendingOp(p.key(), p.type(), p.type() == PendingOp.Type.PUT ? p.data() : null,
                        new ObjectMeta(p.contentType(), p.cacheControl()), p.seq());
                op.spoolFile = file;
                op.spoolSize = Files.size(file);
                bytes.addAndGet(op.spoolSize);
                ops.add(op);
            } catch (IOException | RuntimeException e) {
                Logger.global.logWarning("S3 spool: ignoring unreadable spool file " + name + ": " + e);
                Files.move(file, dir.resolve(name + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return ops;
    }

    private record Parsed(long seq, PendingOp.Type type, String key, String contentType, String cacheControl, byte[] data) {}

    private static Parsed parse(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < 8 + 4 + 1) throw new IOException("truncated");
        ByteBuffer b = ByteBuffer.wrap(all);
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 8);
        if (b.getLong(all.length - 8) != crc.getValue()) throw new IOException("checksum mismatch");
        if (b.getInt() != MAGIC) throw new IOException("bad magic");
        if (b.get() != VERSION) throw new IOException("unsupported version");
        long seq = b.getLong();
        PendingOp.Type type = PendingOp.Type.values()[b.get()];
        String key = str(b);
        String contentType = str(b);
        String cacheControl = str(b);
        byte[] data = new byte[b.getInt()];
        b.get(data);
        return new Parsed(seq, type, key, contentType, cacheControl, data);
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(ByteBuffer b) {
        byte[] raw = new byte[b.getInt()];
        b.get(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
