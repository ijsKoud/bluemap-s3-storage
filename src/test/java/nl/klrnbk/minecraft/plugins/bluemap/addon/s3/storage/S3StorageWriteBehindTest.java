package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.KeyLayout;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.FakeS3;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Client;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Metrics;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue.WriteBehindConfig;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue.WriteBehindObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The real wiring: BlueMap storage classes on top of the write-behind queue. */
class S3StorageWriteBehindTest {

    @TempDir Path tmp;

    @Test
    void renderThreadWritesDoNotWaitForS3AndEverythingIsVisibleImmediately() throws Exception {
        try (FakeS3 fake = new FakeS3()) {
            fake.latencyMillis = 1500;
            S3Client client = new S3Client(fake.config(1, 16, 5000), new S3Metrics());
            var queue = new WriteBehindObjectStore(client, new WriteBehindConfig(4, 1 << 24, 1000, true,
                    tmp.resolve("spool"), 1L << 30, Duration.ofSeconds(20), Duration.ofSeconds(30)));
            S3Storage storage = new S3Storage(client, queue, new KeyLayout("maps", ".gz"), Compression.GZIP,
                    new ObjectKinds("public, max-age=60", "no-cache"), tmp.resolve("rstate"), 300);
            var map = storage.map("world");

            long t0 = System.nanoTime();
            try (var out = map.hiresTiles().write(5, -5)) { out.write("tile".getBytes(StandardCharsets.UTF_8)); }
            try (var out = map.lowresTiles(1).write(0, 0)) { out.write("png".getBytes(StandardCharsets.UTF_8)); }
            try (var out = map.settings().write()) { out.write("{}".getBytes(StandardCharsets.UTF_8)); }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 700, "three writes must not wait for 1500 ms requests");

            assertTrue(fake.objects.isEmpty(), "nothing has reached S3 yet, the read above came from the queue");

            try (var in = map.hiresTiles().read(5, -5); var d = in.decompress()) {
                assertEquals("tile", new String(d.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(map.hiresTiles().exists(5, -5));
            assertEquals(List.of("world"), storage.mapIds().toList(), "pending settings.json makes the map visible");
            try (var cells = map.hiresTiles().stream()) {
                assertEquals(1, cells.count());
            }

            storage.close(); // flushes
            assertNotNull(fake.objects.get("maps/world/tiles/0/x5/z-5.prbm.gz"));
            assertNotNull(fake.objects.get("maps/world/tiles/1/x0/z0.png"));
            assertNotNull(fake.objects.get("maps/world/settings.json"));
        }
    }
}
