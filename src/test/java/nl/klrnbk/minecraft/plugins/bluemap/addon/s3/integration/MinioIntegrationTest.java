package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.integration;

import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Client;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the integration scenario against MinIO started with Docker Compose. Skipped when Docker is not available. */
class MinioIntegrationTest {

    private static final String COMPOSE = "src/test/resources/docker/minio-compose.yml";
    private static final String PROJECT = "bluemap-s3-it";

    @TempDir Path tmp;

    private static boolean run(long timeoutSeconds, String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    @Test
    void storageWorksAgainstMinio() throws Exception {
        boolean dockerOk;
        try {
            dockerOk = run(15, "docker", "compose", "version") && run(20, "docker", "info");
        } catch (Exception e) {
            dockerOk = false;
        }
        assumeTrue(dockerOk, "Docker with Compose is not available, skipping the MinIO integration test");

        try {
            assumeTrue(run(300, "docker", "compose", "-f", COMPOSE, "-p", PROJECT, "up", "-d"), "could not start MinIO");
            var config = IntegrationScenario.clientConfig("http://127.0.0.1:19000", "us-east-1", "it-bucket",
                    "minioadmin", "minioadmin", true);
            waitForBucket(config);
            IntegrationScenario.run(config, tmp);
        } finally {
            run(120, "docker", "compose", "-f", COMPOSE, "-p", PROJECT, "down", "-v");
        }
    }

    /** The bucket is created by a helper container; wait until listing it works. */
    private static void waitForBucket(nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3ClientConfig config) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try (S3Client probe = new S3Client(config, new S3Metrics())) {
                probe.list("", "/", null, 1);
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("MinIO bucket did not become ready", last);
    }
}
