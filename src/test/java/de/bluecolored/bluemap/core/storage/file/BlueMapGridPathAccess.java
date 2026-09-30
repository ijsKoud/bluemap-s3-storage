package de.bluecolored.bluemap.core.storage.file;

import java.nio.file.Path;

/**
 * Test-only bridge: FileGridStorage is package-private in BlueMap 5.3, so the reference
 * implementation for the key layout test is reached from its own package. Test sources only;
 * nothing of this ships in the addon jar.
 */
public final class BlueMapGridPathAccess {
    private BlueMapGridPathAccess() {}

    public static String referenceGridPath(int x, int z, String suffix) {
        FileGridStorage storage = new FileGridStorage(
                Path.of("root"), suffix, de.bluecolored.bluemap.core.storage.compression.Compression.NONE, false);
        // strip the "root/" part and normalise separators
        return Path.of("root").relativize(storage.getItemPath(x, z)).toString().replace('\\', '/');
    }
}
