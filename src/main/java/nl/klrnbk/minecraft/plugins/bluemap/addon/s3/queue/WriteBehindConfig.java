package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue;

import java.nio.file.Path;
import java.time.Duration;

public record WriteBehindConfig(
        int uploadThreads,
        long bufferMaxBytes,
        int bufferMaxEntries,
        boolean spoolEnabled,
        Path spoolPath,
        long spoolMaxBytes,
        Duration shutdownFlushTimeout,
        Duration failedRetryInterval) {}
