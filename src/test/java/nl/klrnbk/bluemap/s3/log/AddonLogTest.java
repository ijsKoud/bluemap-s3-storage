package nl.klrnbk.bluemap.s3.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddonLogTest {

    @TempDir Path tmp;

    record Line(AddonLog.Level level, String message) {}

    @Test
    void consoleLevelFiltersConsoleButFileGetsEverything() throws IOException {
        List<Line> console = new ArrayList<>();
        Path file = tmp.resolve("logs/s3.log");
        try (AddonLog log = new AddonLog(AddonLog.Level.WARN, file, 1 << 20, 3, (l, m, e) -> console.add(new Line(l, m)))) {
            log.log(AddonLog.Level.INFO, "metrics line", null);
            log.log(AddonLog.Level.WARN, "upload failed", null);
            log.log(AddonLog.Level.ERROR, "boom", new IllegalStateException("bad"));
        }
        assertEquals(List.of(new Line(AddonLog.Level.WARN, "upload failed"), new Line(AddonLog.Level.ERROR, "boom")), console);
        String text = Files.readString(file);
        assertTrue(text.contains("INFO  [") && text.contains("metrics line"), text);
        assertTrue(text.contains("WARN  [") && text.contains("upload failed"), text);
        assertTrue(text.contains("ERROR [") && text.contains("java.lang.IllegalStateException: bad"), text);
        assertTrue(text.lines().findFirst().get().matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d INFO  \\[.*\\] metrics line"), text);
    }

    @Test
    void consoleOffPrintsNothingButStillWritesTheFile() throws IOException {
        List<Line> console = new ArrayList<>();
        Path file = tmp.resolve("s3.log");
        try (AddonLog log = new AddonLog(AddonLog.Level.OFF, file, 1 << 20, 3, (l, m, e) -> console.add(new Line(l, m)))) {
            log.log(AddonLog.Level.ERROR, "quiet console", null);
        }
        assertTrue(console.isEmpty());
        assertTrue(Files.readString(file).contains("quiet console"));
    }

    @Test
    void noFileMeansConsoleOnly() {
        List<Line> console = new ArrayList<>();
        AddonLog log = new AddonLog(AddonLog.Level.INFO, null, 0, 0, (l, m, e) -> console.add(new Line(l, m)));
        log.log(AddonLog.Level.INFO, "hello", null);
        assertEquals(1, console.size());
    }

    @Test
    void rotatesAndKeepsTheConfiguredNumberOfFiles() throws IOException {
        Path file = tmp.resolve("s3.log");
        try (AddonLog log = new AddonLog(AddonLog.Level.OFF, file, 1024, 2, (l, m, e) -> {})) {
            for (int i = 0; i < 100; i++) log.log(AddonLog.Level.INFO, "line " + i + " " + "x".repeat(50), null);
        }
        assertTrue(Files.exists(file));
        assertTrue(Files.exists(tmp.resolve("s3.log.1")));
        assertTrue(Files.exists(tmp.resolve("s3.log.2")));
        assertFalse(Files.exists(tmp.resolve("s3.log.3")), "only 2 rotated files are kept");
        assertTrue(Files.size(file) < 1024 + 200);
        // the newest lines are in the current file, older ones in the rotated files
        assertTrue(Files.readString(file).contains("line 99"));
    }

    @Test
    void appendsToAnExistingFileAcrossRestarts() throws IOException {
        Path file = tmp.resolve("s3.log");
        for (String text : List.of("first run", "second run")) {
            try (AddonLog log = new AddonLog(AddonLog.Level.OFF, file, 1 << 20, 3, (l, m, e) -> {})) {
                log.log(AddonLog.Level.INFO, text, null);
            }
        }
        String content = Files.readString(file);
        assertTrue(content.contains("first run") && content.contains("second run"));
    }

    @Test
    void anUnwritableFileNeverBreaksTheCaller() throws IOException {
        Path blocker = tmp.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        List<Line> console = new ArrayList<>();
        AddonLog log = new AddonLog(AddonLog.Level.INFO, blocker.resolve("s3.log"), 1 << 20, 3, (l, m, e) -> console.add(new Line(l, m)));
        assertDoesNotThrow(() -> log.log(AddonLog.Level.INFO, "still works", null));
        assertTrue(console.stream().anyMatch(l -> l.message().equals("still works")));
        assertTrue(console.stream().anyMatch(l -> l.level() == AddonLog.Level.WARN && l.message().contains("cannot write the log file")));
    }
}
