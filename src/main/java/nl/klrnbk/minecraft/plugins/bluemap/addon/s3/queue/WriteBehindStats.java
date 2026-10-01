package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue;

/** Point in time view of the queue for the metrics log. */
public record WriteBehindStats(
        int queuedEntries,
        long queuedBytes,
        int uploadsInFlight,
        long uploadsCompleted,
        long coalescedWrites,
        long failedUploads,
        int failedOpsPending,
        long spoolBytes,
        long producerBlockedNanos) {}
