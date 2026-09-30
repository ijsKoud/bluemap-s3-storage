package nl.klrnbk.bluemap.s3;

import de.bluecolored.bluemap.core.storage.MapStorage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps BlueMap storage locations to S3 object keys. The layout is byte-for-byte the one
 * produced by BlueMap's {@code FileMapStorage} (and by the TheMeinerLP S3 addon), so
 * existing buckets need no migration.
 */
public final class KeyLayout {

    /** Same pattern BlueMap's FileGridStorage uses to parse positions back from a path. */
    private static final Pattern GRID_POSITION = Pattern.compile("x(-?\\d+)z(-?\\d+)");

    private final String prefix;
    private final String compressionSuffix;

    /**
     * @param rootPath          {@code ""} or {@code "."} for the bucket root, otherwise a key prefix
     * @param compressionSuffix file suffix of hires tiles and textures, e.g. {@code ".gz"}
     */
    public KeyLayout(String rootPath, String compressionSuffix) {
        this.prefix = normalizeRoot(rootPath);
        this.compressionSuffix = compressionSuffix;
    }

    /** Returns {@code ""} for the bucket root, otherwise the prefix with exactly one trailing slash. */
    public static String normalizeRoot(String rootPath) {
        if (rootPath == null) return "";
        String p = rootPath.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || p.equals(".")) return "";
        return p + "/";
    }

    /** Prefix of all keys of this storage; empty or ending in '/'. */
    public String rootPrefix() {
        return prefix;
    }

    public String mapPrefix(String mapId) {
        return prefix + mapId + "/";
    }

    /** Grid path as FileGridStorage.getItemPath: a new folder after every digit of {@code x<X>z<Z>}. */
    public static String gridPath(int x, int z) {
        String encoded = "x" + x + "z" + z;
        StringBuilder out = new StringBuilder(encoded.length() * 2);
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            out.append(c);
            if (c >= '0' && c <= '9' && i < encoded.length() - 1) out.append('/');
        }
        return out.toString();
    }

    /**
     * Parses a path (relative to the grid root, with suffix) back to {@code {x, z}}.
     * Returns null if it does not belong to this grid.
     */
    public static int[] parseGridPath(String relativePath, String suffix) {
        if (!relativePath.endsWith(suffix)) return null;
        String name = relativePath.substring(0, relativePath.length() - suffix.length()).replace("/", "");
        Matcher m = GRID_POSITION.matcher(name);
        if (!m.matches()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- grids ----

    public String hiresPrefix(String mapId) {
        return mapPrefix(mapId) + "tiles/0/";
    }

    public String hiresSuffix() {
        return ".prbm" + compressionSuffix;
    }

    public String hiresKey(String mapId, int x, int z) {
        return hiresPrefix(mapId) + gridPath(x, z) + hiresSuffix();
    }

    public String lowresPrefix(String mapId, int lod) {
        return mapPrefix(mapId) + "tiles/" + lod + "/";
    }

    public static String lowresSuffix() {
        return ".png";
    }

    public String lowresKey(String mapId, int lod, int x, int z) {
        return lowresPrefix(mapId, lod) + gridPath(x, z) + lowresSuffix();
    }

    // ---- render state (only used for the one-time import, then it lives on local disk) ----

    public String renderStatePrefix(String mapId) {
        return mapPrefix(mapId) + "rstate/";
    }

    // ---- items ----

    public String settingsKey(String mapId) {
        return mapPrefix(mapId) + "settings.json";
    }

    public String texturesKey(String mapId) {
        return mapPrefix(mapId) + "textures.json" + compressionSuffix;
    }

    public String markersKey(String mapId) {
        return mapPrefix(mapId) + "live/markers.json";
    }

    public String playersKey(String mapId) {
        return mapPrefix(mapId) + "live/players.json";
    }

    public String assetKey(String mapId, String assetName) {
        return mapPrefix(mapId) + "assets/" + MapStorage.escapeAssetName(assetName);
    }
}
