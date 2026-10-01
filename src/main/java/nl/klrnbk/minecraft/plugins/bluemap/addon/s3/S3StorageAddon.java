package nl.klrnbk.minecraft.plugins.bluemap.addon.s3;

import de.bluecolored.bluemap.common.config.storage.StorageConfig;
import de.bluecolored.bluemap.common.config.storage.StorageType;
import de.bluecolored.bluemap.core.util.Key;

/** Addon entrypoint: registers the {@code klrnbk-bluemap:s3} storage type with BlueMap. */
public final class S3StorageAddon implements Runnable {

    public static final Key TYPE_KEY = new Key("klrnbk-bluemap", "s3");

    @Override
    public void run() {
        StorageType.REGISTRY.register(new StorageType() {
            @Override
            public Key getKey() {
                return TYPE_KEY;
            }

            @Override
            public Class<? extends StorageConfig> getConfigType() {
                return S3StorageConfig.class;
            }
        });
    }
}
