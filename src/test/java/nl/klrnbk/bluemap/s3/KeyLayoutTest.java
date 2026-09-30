package nl.klrnbk.bluemap.s3;

import de.bluecolored.bluemap.core.storage.file.BlueMapGridPathAccess;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class KeyLayoutTest {

    @Test
    void gridPathMatchesBlueMapForManyCoordinates() {
        Random random = new Random(42);
        int[][] fixed = {{0, 0}, {-1, -1}, {5, -5}, {10, 100}, {-10, -100}, {123456, -654321},
                {Integer.MAX_VALUE, Integer.MIN_VALUE}, {0, -2147483648}};
        for (int[] p : fixed) assertSame(p[0], p[1]);
        for (int i = 0; i < 500; i++) {
            int bound = 1 << (1 + random.nextInt(22));
            assertSame(random.nextInt(bound * 2) - bound, random.nextInt(bound * 2) - bound);
        }
    }

    private static void assertSame(int x, int z) {
        for (String suffix : new String[]{".prbm.gz", ".png", ".tiles.dat"}) {
            assertEquals(BlueMapGridPathAccess.referenceGridPath(x, z, suffix),
                    KeyLayout.gridPath(x, z) + suffix, "x=" + x + " z=" + z);
        }
    }

    @Test
    void parsesGridPathsBack() {
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) {
            int x = random.nextInt(200_000) - 100_000, z = random.nextInt(200_000) - 100_000;
            int[] parsed = KeyLayout.parseGridPath(KeyLayout.gridPath(x, z) + ".png", ".png");
            assertArrayEquals(new int[]{x, z}, parsed);
        }
        assertNull(KeyLayout.parseGridPath("x1/z2.png", ".gz"));
        assertNull(KeyLayout.parseGridPath("garbage.png", ".png"));
        assertNull(KeyLayout.parseGridPath("x99999999999z1.png", ".png"));
    }

    @Test
    void rootPathHandling() {
        assertEquals("", KeyLayout.normalizeRoot(""));
        assertEquals("", KeyLayout.normalizeRoot("."));
        assertEquals("", KeyLayout.normalizeRoot(null));
        assertEquals("", KeyLayout.normalizeRoot("/"));
        assertEquals("prefix/", KeyLayout.normalizeRoot("prefix"));
        assertEquals("a/b/", KeyLayout.normalizeRoot("/a/b/"));
    }

    @Test
    void itemKeys() {
        KeyLayout l = new KeyLayout("maps", ".gz");
        assertEquals("maps/world/tiles/0/x1/2/z-3.prbm.gz", l.hiresKey("world", 12, -3));
        assertEquals("maps/world/tiles/2/x-1/z0.png", l.lowresKey("world", 2, -1, 0));
        assertEquals("maps/world/settings.json", l.settingsKey("world"));
        assertEquals("maps/world/textures.json.gz", l.texturesKey("world"));
        assertEquals("maps/world/live/markers.json", l.markersKey("world"));
        assertEquals("maps/world/live/players.json", l.playersKey("world"));
        assertEquals("maps/world/assets/a/b_c.png", l.assetKey("world", "a/b c.png"));
        assertEquals("world/tiles/0/x0/z0.prbm", new KeyLayout(".", "").hiresKey("world", 0, 0));
        assertEquals("maps/world/rstate/", l.renderStatePrefix("world"));
    }
}
