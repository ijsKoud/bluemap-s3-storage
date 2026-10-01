package nl.klrnbk.minecraft.plugins.bluemap.addon.s3;

import de.bluecolored.bluemap.common.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.util.NamingSchemes;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class S3StorageConfigTest {

    private static S3StorageConfig parse(String hocon) throws IOException {
        var loader = HoconConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(new java.io.StringReader(hocon)))
                .defaultOptions(o -> o.serializers(b -> b.registerAnnotatedObjects(
                        ObjectMapper.factoryBuilder().defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED).build())))
                .build();
        CommentedConfigurationNode node = loader.load();
        return node.get(S3StorageConfig.class);
    }

    @Test
    void defaultsAndOverrides() throws Exception {
        S3StorageConfig c = parse("""
                storage-type: "klrnbk-bluemap:s3"
                bucket-name: "my-bucket"
                access-key-id: "ak"
                secret-access-key: "sk"
                upload-threads: 8
                root-path: "maps"
                compression: "none"
                """);
        assertEquals("my-bucket", c.getBucketName());
        assertEquals(8, c.getUploadThreads());
        assertEquals("https://hel1.your-objectstorage.com", c.getEndpointUrl());
        assertEquals("hel1", c.getRegion());
        assertEquals(32, c.getMaxInFlightRequests());
        assertEquals(600, c.getMaxRequestsPerSecond());
        assertEquals(268_435_456L, c.getWriteBufferMaxBytes());
        assertEquals(20_000, c.getWriteBufferMaxEntries());
        assertTrue(c.isSpoolEnabled());
        assertEquals("bluemap/rstate-s3", c.getRenderStatePath());
        assertEquals("public, max-age=60", c.getTileCacheControl());
        assertEquals("no-cache", c.getMetaCacheControl());
        assertEquals(300, c.getListCacheTtlSeconds());
        assertEquals(5, c.getRequestTimeoutSeconds());
        assertEquals(4, c.getReadTimeoutSeconds());
        assertEquals(10, c.getReadMaxRetries());
        assertEquals("", c.keyLayout().hiresKey("w", 0, 0).isEmpty() ? "x" : "");
        assertTrue(c.keyLayout().hiresKey("w", 0, 0).startsWith("maps/w/tiles/0/"));
        assertTrue(c.keyLayout().hiresKey("w", 0, 0).endsWith(".prbm"));
        assertDoesNotThrow(c::validate);
    }

    @Test
    void spoolPathAndThreadCountAreConfigurable() throws Exception {
        S3StorageConfig c = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                upload-threads: 32
                max-in-flight-requests: 48
                spool-path: "/data/bluemap/s3-spool"
                """);
        assertEquals(32, c.getUploadThreads());
        assertEquals(48, c.getMaxInFlightRequests());
        assertEquals("/data/bluemap/s3-spool", c.getSpoolPath());
        assertTrue(java.nio.file.Path.of(c.getSpoolPath()).isAbsolute());
        assertEquals("bluemap/s3-spool", parse("bucket-name: b").getSpoolPath()); // default
        assertDoesNotThrow(c::validate);
    }

    @Test
    void unknownKeysOfTheOldAddonAreIgnored() throws Exception {
        // keys that only the TheMeinerLP addon knows must not break a migrated config
        S3StorageConfig c = parse("""
                storage-type: "klrnbk-bluemap:s3"
                bucket-name: "b"
                access-key-id: "a"
                secret-access-key: "s"
                checksum-validation: "when_required"
                provider: ""
                account-id: ""
                root-path: "."
                """);
        assertEquals("b", c.getBucketName());
        assertEquals("", c.keyLayout().rootPrefix());
        assertDoesNotThrow(c::validate);
    }

    @Test
    void logSettings() throws Exception {
        S3StorageConfig c = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                log-file: "bluemap/s3-storage.log"
                console-log-level: "warn"
                log-file-max-bytes: 2048
                log-file-keep: 5
                """);
        assertEquals("bluemap/s3-storage.log", c.getLogFile());
        assertEquals("warn", c.getConsoleLogLevel());
        assertEquals(5, c.getLogFileKeep());
        assertDoesNotThrow(c::validate);
        S3StorageConfig defaults = parse("bucket-name: b");
        assertEquals("", defaults.getLogFile());
        assertEquals("info", defaults.getConsoleLogLevel());
        // console off without a file would swallow errors
        S3StorageConfig off = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                console-log-level: "off"
                """);
        assertThrows(ConfigurationException.class, off::validate);
        S3StorageConfig bad = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                console-log-level: "loud"
                """);
        assertThrows(ConfigurationException.class, bad::validate);
    }

    @Test
    void validationRejectsBadValues() throws Exception {
        S3StorageConfig empty = parse("storage-type: \"klrnbk-bluemap:s3\"");
        assertThrows(ConfigurationException.class, empty::validate); // bucket and keys missing
        S3StorageConfig badCompression = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                compression: nope
                """);
        assertThrows(ConfigurationException.class, badCompression::validate);
        S3StorageConfig badThreads = parse("""
                bucket-name: b
                access-key-id: a
                secret-access-key: s
                upload-threads: 0
                """);
        assertThrows(ConfigurationException.class, badThreads::validate);
    }

    @Test
    void addonRegistersStorageType() {
        new S3StorageAddon().run();
        var type = de.bluecolored.bluemap.common.config.storage.StorageType.REGISTRY.get(S3StorageAddon.TYPE_KEY);
        assertNotNull(type);
        assertEquals(S3StorageConfig.class, type.getConfigType());
    }
}
