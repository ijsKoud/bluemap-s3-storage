package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.integration;

import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.KeyLayout;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.ListPage;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Client;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3ClientConfig;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Metrics;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue.WriteBehindConfig;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.queue.WriteBehindObjectStore;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage.DirectObjectStore;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage.ObjectKinds;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage.S3Storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same checks against any real S3 endpoint (MinIO, R2, Hetzner, ...). Everything happens under a random
 * prefix that is deleted at the end, so a shared bucket is left as it was found.
 */
final class IntegrationScenario {

    private IntegrationScenario() {}

    static S3ClientConfig clientConfig(String endpoint, String region, String bucket, String accessKey, String secretKey,
                                       boolean pathStyle) {
        return new S3ClientConfig(endpoint, region, bucket, accessKey, secretKey, pathStyle, Duration.ofSeconds(10),
                Duration.ofSeconds(15), Duration.ofSeconds(10), 3, 8, 200, Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    static void run(S3ClientConfig config, Path workDir) throws Exception {
        String prefix = "it-" + UUID.randomUUID() + "/";
        S3Client client = new S3Client(config, new S3Metrics());
        try {
            rawClient(client, prefix);
            storageOnTopOfTheQueue(config, client, prefix, workDir);
        } finally {
            try {
                new DirectObjectStore(client).deletePrefix(prefix, p -> true);
            } finally {
                client.close();
            }
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void rawClient(S3Client client, String prefix) throws IOException {
        String key = prefix + "raw/a b+c/é&=?.bin";
        client.put(key, bytes("hello"), "application/octet-stream", "public, max-age=60");
        assertArrayEquals(bytes("hello"), client.get(key));
        assertTrue(client.head(key));
        assertNull(client.get(prefix + "raw/missing"));
        assertFalse(client.head(prefix + "raw/missing"));
        client.delete(key);
        assertNull(client.get(key));
        client.delete(key); // deleting a missing key is not an error

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String k = String.format("%slist/k%02d", prefix, i);
            client.put(k, bytes("v" + i), "text/plain", null);
            keys.add(k);
        }
        List<String> listed = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            ListPage page = client.list(prefix + "list/", null, token, 10);
            listed.addAll(page.keys());
            token = page.nextToken();
            pages++;
        } while (token != null);
        assertEquals(keys, listed.stream().sorted().toList());
        assertTrue(pages >= 3, "pagination, pages: " + pages);
        assertEquals(List.of(prefix + "list/"), client.list(prefix, "/", null, 0).commonPrefixes());

        assertEquals(List.of(), client.deleteObjects(keys));
        assertTrue(client.list(prefix + "list/", null, null, 0).keys().isEmpty());
    }

    private static void storageOnTopOfTheQueue(S3ClientConfig config, S3Client client, String prefix, Path workDir) throws Exception {
        String root = prefix + "maps";
        WriteBehindObjectStore queue = new WriteBehindObjectStore(client, new WriteBehindConfig(4, 1 << 24, 1000, true,
                workDir.resolve("spool"), 1L << 28, Duration.ofSeconds(30), Duration.ofSeconds(30)));
        S3Storage storage = new S3Storage(client, queue, new KeyLayout(root, ".gz"), Compression.GZIP,
                new ObjectKinds("public, max-age=60", "no-cache"), workDir.resolve("rstate"), 0);
        var map = storage.map("world");
        storage.initialize();

        try (var out = map.hiresTiles().write(3, -4)) { out.write(bytes("hires")); }
        try (var out = map.lowresTiles(1).write(0, 0)) { out.write(bytes("png")); }
        try (var out = map.settings().write()) { out.write(bytes("{}")); }
        try (var in = map.hiresTiles().read(3, -4); var d = in.decompress()) {
            assertEquals("hires", new String(d.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("world"), storage.mapIds().toList());

        queue.close(); // flushes everything to the bucket

        assertNotNull(client.get(root + "/world/tiles/0/x3/z-4.prbm.gz"));
        assertArrayEquals(bytes("png"), client.get(root + "/world/tiles/1/x0/z0.png"));
        assertTrue(map.exists());
        try (var cells = map.hiresTiles().stream()) {
            assertEquals(1, cells.count());
        }

        // a fresh storage without queue: reads come straight from the bucket, then the map is deleted
        S3Storage direct = new S3Storage(client, new DirectObjectStore(client), new KeyLayout(root, ".gz"), Compression.GZIP,
                new ObjectKinds("a", "b"), workDir.resolve("rstate2"), 0);
        var dmap = direct.map("world");
        try (var in = dmap.hiresTiles().read(3, -4); var d = in.decompress()) {
            assertEquals("hires", new String(d.readAllBytes(), StandardCharsets.UTF_8));
        }
        List<Double> progress = new ArrayList<>();
        dmap.delete(p -> { progress.add(p); return true; });
        assertFalse(progress.isEmpty());
        assertFalse(dmap.exists());
        assertNull(client.get(root + "/world/settings.json"));
    }
}
