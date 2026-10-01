package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in test against a real bucket, driven by environment variables:
 * S3_TEST_ENDPOINT, S3_TEST_BUCKET, S3_TEST_REGION, S3_TEST_ACCESS_KEY, S3_TEST_SECRET_KEY
 * and optionally S3_TEST_PATH_STYLE (default true). Everything is created under a random prefix and deleted afterwards.
 * Note that every run makes a few hundred requests, which count towards provider quotas.
 */
class RealBucketIntegrationTest {

    @TempDir Path tmp;

    @Test
    void storageWorksAgainstARealBucket() throws Exception {
        String endpoint = System.getenv("S3_TEST_ENDPOINT");
        String bucket = System.getenv("S3_TEST_BUCKET");
        String region = System.getenv("S3_TEST_REGION");
        String accessKey = System.getenv("S3_TEST_ACCESS_KEY");
        String secretKey = System.getenv("S3_TEST_SECRET_KEY");
        assumeTrue(endpoint != null && bucket != null && region != null && accessKey != null && secretKey != null,
                "S3_TEST_* environment variables are not set, skipping the real bucket test");
        boolean pathStyle = !"false".equalsIgnoreCase(System.getenv().getOrDefault("S3_TEST_PATH_STYLE", "true"));

        IntegrationScenario.run(IntegrationScenario.clientConfig(endpoint, region, bucket, accessKey, secretKey, pathStyle), tmp);
    }
}
