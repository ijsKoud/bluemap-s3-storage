package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.log;

import de.bluecolored.bluemap.core.logger.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Log output of the addon (metrics line, upload failures, spool and shutdown messages), routed to
 * BlueMap's console, to a rotating file, or both. This lets people keep the console quiet while the
 * details stay available in a file.
 *
 * <p>The file always receives everything from INFO up. The console receives messages at or above its own
 * level; with level OFF nothing is printed (the configuration requires a log file in that case).
 * Settings are process wide: the most recently created storage installs its settings.
 */
public final class AddonLog implements AutoCloseable {

    public enum Level {
        INFO, WARN, ERROR, OFF;

        public static Level parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** Where console messages go; the default writes to BlueMap's logger. */
    public interface ConsoleSink {
        void log(Level level, String message, Throwable error);
    }

    public static final ConsoleSink BLUEMAP_CONSOLE = (level, message, error) -> {
        switch (level) {
            case INFO -> Logger.global.logInfo(message);
            case WARN -> Logger.global.logWarning(message);
            default -> {
                if (error != null) Logger.global.logError(message, error);
                else Logger.global.logError(message, null);
            }
        }
    };

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile AddonLog current = new AddonLog(Level.INFO, null, 0, 0, BLUEMAP_CONSOLE);

    private final Level consoleLevel;
    private final Path file;
    private final long maxBytes;
    private final int keep;
    private final ConsoleSink console;
    private BufferedWriter writer;
    private long size;

    /**
     * @param file     log file, or null for console only
     * @param maxBytes rotate when the file reaches this size
     * @param keep     number of rotated files to keep (file.1 is the newest)
     */
    public AddonLog(Level consoleLevel, Path file, long maxBytes, int keep, ConsoleSink console) {
        this.consoleLevel = consoleLevel;
        this.file = file;
        this.maxBytes = maxBytes;
        this.keep = Math.max(1, keep);
        this.console = console;
    }

    /** Makes this the log used by all addon classes and closes the previous one. */
    public static void install(AddonLog log) {
        AddonLog old = current;
        current = log;
        if (old != null && old != log) old.close();
    }

    public static void info(String message) {
        current.log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        current.log(Level.WARN, message, null);
    }

    public static void error(String message, Throwable error) {
        current.log(Level.ERROR, message, error);
    }

    public void log(Level level, String message, Throwable error) {
        if (consoleLevel != Level.OFF && level.ordinal() >= consoleLevel.ordinal()) {
            console.log(level, message, error);
        }
        if (file != null) writeToFile(level, message, error);
    }

    private synchronized void writeToFile(Level level, String message, Throwable error) {
        try {
            if (writer == null) open();
            StringBuilder line = new StringBuilder(message.length() + 64);
            line.append(TIME.format(LocalDateTime.now())).append(' ')
                    .append(String.format("%-5s", level)).append(" [")
                    .append(Thread.currentThread().getName()).append("] ").append(message).append('\n');
            if (error != null) {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            writer.write(line.toString());
            writer.flush();
            size += line.toString().getBytes(StandardCharsets.UTF_8).length;
            if (maxBytes > 0 && size >= maxBytes) rotate();
        } catch (IOException e) {
            // Never let logging break uploads. Report once on the console and stop using the file.
            console.log(Level.WARN, "S3 storage: cannot write the log file " + file + ": " + e, null);
            closeWriterQuietly();
        }
    }

    private void open() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        size = Files.exists(file) ? Files.size(file) : 0;
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void rotate() throws IOException {
        closeWriterQuietly();
        Files.deleteIfExists(rotated(keep));
        for (int i = keep - 1; i >= 1; i--) {
            if (Files.exists(rotated(i))) Files.move(rotated(i), rotated(i + 1), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(file, rotated(1), StandardCopyOption.REPLACE_EXISTING);
        open();
    }

    private Path rotated(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    private void closeWriterQuietly() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // nothing sensible to do
        }
        writer = null;
    }

    @Override
    public synchronized void close() {
        closeWriterQuietly();
    }
}
