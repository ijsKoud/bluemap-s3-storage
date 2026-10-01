package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.KeyLayout;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.FakeS3;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Client;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class S3StorageSemanticsTest {

    @TempDir Path tmp;
    FakeS3 fake;
    S3Storage storage;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeS3();
        storage = create("maps", Compression.GZIP);
    }

    @AfterEach
    void tearDown() throws IOException {
        storage.close();
        fake.close();
    }

    S3Storage create(String root, Compression compression) {
        S3Client client = new S3Client(fake.config(2, 8, 5000), new S3Metrics());
        return new S3Storage(client, new DirectObjectStore(client), new KeyLayout(root, compression.getFileSuffix()),
                compression, new ObjectKinds("public, max-age=60", "no-cache"), tmp.resolve("rstate"), 300);
    }

    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static void write(OutputStream out, byte[] data) throws IOException {
        try (out) { out.write(data); }
    }

    static byte[] readAll(CompressedInputStream in) throws IOException {
        try (in; var d = in.decompress()) { return d.readAllBytes(); }
    }

    static byte[] gunzip(byte[] data) throws IOException {
        return new GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes();
    }

    @Test
    void hiresTileRoundTripUsesBlueMapKeyLayoutAndHeaders() throws IOException {
        var map = storage.map("world");
        write(map.hiresTiles().write(12, -3), bytes("tile-data"));

        String key = "maps/world/tiles/0/x1/2/z-3.prbm.gz";
        assertNotNull(fake.objects.get(key), fake.objects.keySet().toString());
        assertEquals("tile-data", new String(gunzip(fake.data(key)), StandardCharsets.UTF_8));
        assertEquals("application/octet-stream", fake.objects.get(key).contentType());
        assertEquals("public, max-age=60", fake.objects.get(key).cacheControl());

        assertEquals("tile-data", new String(readAll(map.hiresTiles().read(12, -3)), StandardCharsets.UTF_8));
        assertTrue(map.hiresTiles().exists(12, -3));
        map.hiresTiles().delete(12, -3);
        assertNull(map.hiresTiles().read(12, -3));
        assertFalse(map.hiresTiles().exists(12, -3));
    }

    @Test
    void readIsOneGetAndExistsIsOneHead() throws IOException {
        var map = storage.map("world");
        write(map.lowresTiles(1).write(0, 0), bytes("png"));
        fake.log.clear();
        readAll(map.lowresTiles(1).read(0, 0));
        assertEquals(List.of("GET /test-bucket/maps/world/tiles/1/x0/z0.png"), List.copyOf(fake.log));
        fake.log.clear();
        assertTrue(map.lowresTiles(1).exists(0, 0));
        assertEquals(List.of("HEAD /test-bucket/maps/world/tiles/1/x0/z0.png"), List.copyOf(fake.log));
    }

    @Test
    void lowresIsStoredUncompressedWithPngContentType() throws IOException {
        write(storage.map("world").lowresTiles(2).write(-1, 5), bytes("PNGDATA"));
        var o = fake.objects.get("maps/world/tiles/2/x-1/z5.png");
        assertEquals("PNGDATA", new String(o.data(), StandardCharsets.UTF_8));
        assertEquals("image/png", o.contentType());
    }

    @Test
    void itemsUseFileStorageKeysAndContentTypes() throws IOException {
        var map = storage.map("world");
        write(map.settings().write(), bytes("{}"));
        write(map.markers().write(), bytes("{}"));
        write(map.players().write(), bytes("{}"));
        write(map.textures().write(), bytes("{\"t\":1}"));
        write(map.asset("icons/a b.png").write(), bytes("x"));

        assertEquals("application/json", fake.objects.get("maps/world/settings.json").contentType());
        assertEquals("no-cache", fake.objects.get("maps/world/settings.json").cacheControl());
        assertEquals("application/json", fake.objects.get("maps/world/live/markers.json").contentType());
        assertEquals("application/json", fake.objects.get("maps/world/live/players.json").contentType());
        var textures = fake.objects.get("maps/world/textures.json.gz");
        assertNotNull(textures);
        assertEquals("application/octet-stream", textures.contentType()); // no Content-Encoding semantics
        assertEquals("{\"t\":1}", new String(gunzip(textures.data()), StandardCharsets.UTF_8));
        assertEquals("image/png", fake.objects.get("maps/world/assets/icons/a_b.png").contentType());
        assertEquals("{\"t\":1}", new String(readAll(map.textures().read()), StandardCharsets.UTF_8));
    }

    @Test
    void uncompressedStorageUsesPlainSuffixesAndJsonTextures() throws IOException {
        try (S3Storage plain = create("", Compression.NONE)) {
            write(plain.map("w").textures().write(), bytes("{}"));
            write(plain.map("w").hiresTiles().write(0, 0), bytes("t"));
            assertEquals("application/json", fake.objects.get("w/textures.json").contentType());
            assertNotNull(fake.objects.get("w/tiles/0/x0/z0.prbm"));
        }
    }

    @Test
    void dotRootMeansBucketRoot() throws IOException {
        try (S3Storage dot = create(".", Compression.GZIP)) {
            write(dot.map("w").settings().write(), bytes("{}"));
            assertNotNull(fake.objects.get("w/settings.json"));
        }
    }

    @Test
    void identicalSettingsMarkersAndPlayersAreNotWrittenAgain() throws IOException {
        var map = storage.map("world");
        for (int i = 0; i < 5; i++) { // BlueMap saves these on every map save with the same content
            write(map.settings().write(), bytes("{\"a\":1}"));
            write(map.markers().write(), bytes("{}"));
            write(map.players().write(), bytes("{}"));
        }
        assertEquals(3, fake.mutations.stream().filter(m -> m.startsWith("PUT")).count(), fake.mutations.toString());

        write(map.settings().write(), bytes("{\"a\":2}")); // changed content is written
        assertEquals(4, fake.mutations.stream().filter(m -> m.startsWith("PUT")).count());
        assertEquals("{\"a\":2}", new String(fake.data("maps/world/settings.json"), StandardCharsets.UTF_8));

        map.markers().delete(); // after a delete the same content must be written again
        write(map.markers().write(), bytes("{}"));
        assertEquals(5, fake.mutations.stream().filter(m -> m.startsWith("PUT")).count());
        assertNotNull(fake.data("maps/world/live/markers.json"));
    }

    @Test
    void tilesAreNeverDeduplicated() throws IOException {
        var grid = storage.map("world").hiresTiles();
        for (int i = 0; i < 3; i++) write(grid.write(0, 0), bytes("same"));
        assertEquals(3, fake.mutations.stream().filter(m -> m.startsWith("PUT")).count());
    }

    @Test
    void deletingTheMapForgetsRememberedContent() throws IOException {
        var map = storage.map("world");
        write(map.settings().write(), bytes("{}"));
        map.delete(p -> true);
        assertNull(fake.data("maps/world/settings.json"));
        write(map.settings().write(), bytes("{}")); // same content as before the delete
        assertNotNull(fake.data("maps/world/settings.json"));
    }

    @Test
    void writeIsVisibleOnlyAfterClose() throws IOException {
        var out = storage.map("world").hiresTiles().write(1, 1);
        out.write(bytes("abc"));
        assertTrue(fake.objects.isEmpty());
        out.close();
        out.close(); // double close commits once
        assertEquals(1, fake.objects.size());
        assertEquals(1, fake.log.stream().filter(l -> l.startsWith("PUT")).count());
    }

    @Test
    void streamListsCellsIncludingNegativesAndIgnoresForeignKeys() throws IOException {
        var grid = storage.map("world").hiresTiles();
        Set<List<Integer>> expected = new HashSet<>();
        Random r = new Random(1);
        for (int i = 0; i < 1200; i++) { // more than one list page
            int x = r.nextInt(2000) - 1000, z = r.nextInt(2000) - 1000;
            if (expected.add(List.of(x, z))) fake.objects.put(new KeyLayout("maps", ".gz").hiresKey("world", x, z),
                    new FakeS3.StoredObject(new byte[1], null, null));
        }
        fake.objects.put("maps/world/tiles/0/readme.txt", new FakeS3.StoredObject(new byte[1], null, null));
        fake.objects.put("maps/world/tiles/0/x1/z1.prbm", new FakeS3.StoredObject(new byte[1], null, null)); // wrong suffix
        Set<List<Integer>> actual;
        try (var stream = grid.stream()) {
            actual = stream.map(c -> List.of(c.getX(), c.getZ())).collect(Collectors.toSet());
        }
        assertEquals(expected, actual);
    }

    @Test
    void mapIdsOnlyContainsPrefixesWithSettingsAndIsCached() throws IOException {
        write(storage.map("a").settings().write(), bytes("{}"));
        write(storage.map("b").hiresTiles().write(0, 0), bytes("x")); // no settings.json
        fake.objects.put("maps/c/settings.json", new FakeS3.StoredObject(new byte[1], null, null));
        assertEquals(List.of("a", "c"), storage.mapIds().sorted().toList());
        int requests = fake.requests.get();
        assertEquals(List.of("a", "c"), storage.mapIds().sorted().toList());
        assertEquals(requests, fake.requests.get(), "second call must be served from the cache");
    }

    @Test
    void newMapIsVisibleAfterSettingsWriteDespiteCache() throws IOException {
        assertTrue(storage.mapIds().toList().isEmpty());
        write(storage.map("new").settings().write(), bytes("{}"));
        assertEquals(List.of("new"), storage.mapIds().toList());
    }

    @Test
    void mapExistsAndDeleteRemovesEverythingWithProgress() throws IOException {
        var map = storage.map("w");
        assertFalse(map.exists());
        for (int i = 0; i < 2300; i++) fake.objects.put("maps/w/tiles/0/x" + i + "/z0.prbm.gz", new FakeS3.StoredObject(new byte[1], null, null));
        fake.objects.put("maps/other/settings.json", new FakeS3.StoredObject(new byte[1], null, null));
        assertTrue(map.exists());

        // local render state that must disappear too
        write(map.tileState().write(0, 0), bytes("state"));
        assertTrue(Files.exists(tmp.resolve("rstate/w/rstate")));

        List<Double> progress = new ArrayList<>();
        map.delete(p -> { progress.add(p); return true; });
        assertEquals(Set.of("maps/other/settings.json"), fake.objects.keySet());
        assertEquals(3, progress.size());
        assertEquals(1d, progress.get(2), 1e-9);
        assertFalse(map.exists());
        assertFalse(Files.exists(tmp.resolve("rstate/w/rstate")));
        assertFalse(map.tileState().exists(0, 0));
    }

    @Test
    void deleteHonoursCancellation() throws IOException {
        for (int i = 0; i < 2500; i++) fake.objects.put("maps/w/k" + i, new FakeS3.StoredObject(new byte[1], null, null));
        storage.map("w").delete(p -> false);
        assertEquals(1500, fake.objects.size());
    }

    @Test
    void renderStateStaysLocalAndNeverTouchesS3() throws IOException {
        var map = storage.map("world");
        for (GridStorage grid : List.of(map.tileState(), map.chunkState(), map.regionState())) {
            write(grid.write(3, -4), bytes("state"));
            assertEquals("state", new String(readAll(grid.read(3, -4)), StandardCharsets.UTF_8));
            assertEquals(1, grid.stream().count());
        }
        assertTrue(Files.exists(tmp.resolve("rstate/world/rstate/x3/z-4.tiles.dat")));
        assertTrue(Files.exists(tmp.resolve("rstate/world/rstate/x3/z-4.chunks.dat")));
        assertTrue(Files.exists(tmp.resolve("rstate/world/rstate/regions/x3/z-4.regions.dat")));
        assertTrue(fake.objects.isEmpty());
        assertNull(map.tileState().read(9, 9));
        // tiles and chunks share a directory but must not see each other
        assertEquals(1, map.tileState().stream().count());
    }

    @Test
    void renderStateIsImportedOnceFromBucket() throws IOException {
        KeyLayout l = new KeyLayout("maps", ".gz");
        Map<String, byte[]> state = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            state.put("maps/world/rstate/" + KeyLayout.gridPath(i, -i) + ".tiles.dat", gz("tiles" + i));
            state.put("maps/world/rstate/" + KeyLayout.gridPath(i, i) + ".chunks.dat", gz("chunks" + i));
        }
        state.put("maps/world/rstate/regions/" + KeyLayout.gridPath(2, 3) + ".regions.dat", gz("regions"));
        state.put("maps/world/rstate/garbage.txt", gz("ignored"));
        state.forEach((k, v) -> fake.objects.put(k, new FakeS3.StoredObject(v, null, null)));
        fake.objects.put(l.hiresKey("world", 0, 0), new FakeS3.StoredObject(new byte[1], null, null));

        var map = storage.map("world");
        assertEquals("tiles7", new String(readAll(map.tileState().read(7, -7)), StandardCharsets.UTF_8));
        assertEquals("regions", new String(readAll(map.regionState().read(2, 3)), StandardCharsets.UTF_8));
        assertEquals(40, map.tileState().stream().count());
        assertEquals(40, map.chunkState().stream().count());
        assertFalse(Files.exists(tmp.resolve("rstate/world/rstate/garbage.txt")));
        assertTrue(Files.exists(tmp.resolve("rstate/world/.s3-imported")));

        int requests = fake.requests.get();
        readAll(map.tileState().read(1, -1));
        write(map.tileState().write(100, 100), bytes("new"));
        assertEquals(requests, fake.requests.get(), "after the import, state access must not use the network");
    }

    @Test
    void interruptedImportResumesAndKeepsCompleteFiles() throws IOException {
        for (int i = 0; i < 10; i++)
            fake.objects.put("maps/world/rstate/" + KeyLayout.gridPath(i, 0) + ".tiles.dat", new FakeS3.StoredObject(gz("remote" + i), null, null));
        // first attempt: the bucket fails while listing -> no marker
        fake.inject(FakeS3.Fault.status(403, "AccessDenied"), 1, r -> r.startsWith("GET") && !r.contains("/tiles/"));
        var map = storage.map("world");
        assertThrows(IOException.class, () -> map.tileState().read(0, 0));
        assertFalse(Files.exists(tmp.resolve("rstate/world/.s3-imported")));

        // a file from the partial import already exists locally and is kept as is
        Path existing = tmp.resolve("rstate/world/rstate/x3/z0.tiles.dat");
        Files.createDirectories(existing.getParent());
        Files.write(existing, gz("local3"));
        assertEquals("local3", new String(readAll(map.tileState().read(3, 0)), StandardCharsets.UTF_8));
        assertEquals("remote4", new String(readAll(map.tileState().read(4, 0)), StandardCharsets.UTF_8));
        assertTrue(Files.exists(tmp.resolve("rstate/world/.s3-imported")));
    }

    @Test
    void closedStorageRejectsOperations() throws IOException {
        var map = storage.map("world");
        storage.close();
        assertTrue(storage.isClosed());
        assertTrue(map.isClosed());
        assertThrows(IOException.class, () -> map.settings().write());
        assertThrows(IOException.class, () -> map.hiresTiles().read(0, 0));
        assertThrows(IOException.class, storage::mapIds);
        storage.close(); // idempotent
    }

    @Test
    void initializeFailsOnBadBucketAndPassesOnGoodOne() throws IOException {
        storage.initialize();
        var cfg = fake.config(0, 4, 100);
        S3Client client = new S3Client(new nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3ClientConfig(cfg.endpointUrl(), cfg.region(), "nope",
                cfg.accessKeyId(), cfg.secretAccessKey(), true, cfg.connectTimeout(), cfg.requestTimeout(), 0, 4, 100,
                cfg.backoffBase(), cfg.backoffCap()), new S3Metrics());
        try (S3Storage bad = new S3Storage(client, new DirectObjectStore(client), new KeyLayout("", ".gz"), Compression.GZIP,
                new ObjectKinds("a", "b"), tmp.resolve("x"), 0)) {
            assertThrows(IOException.class, bad::initialize);
        }
    }

    @Test
    void rejectsSuspiciousMapIds() {
        assertThrows(IllegalArgumentException.class, () -> storage.map("../evil"));
        assertThrows(IllegalArgumentException.class, () -> storage.map(".."));
        assertThrows(IllegalArgumentException.class, () -> storage.map("a/b"));
        assertSame(storage.map("ok_map-1"), storage.map("ok_map-1"));
    }

    static byte[] gz(String s) throws IOException {
        var bos = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(bos)) { g.write(bytes(s)); }
        return bos.toByteArray();
    }
}
