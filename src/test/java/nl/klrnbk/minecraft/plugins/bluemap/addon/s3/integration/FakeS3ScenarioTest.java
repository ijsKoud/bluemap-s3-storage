package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.integration;

import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.FakeS3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Always runs the integration scenario against the in-process fake, so the scenario itself stays correct. */
class FakeS3ScenarioTest {

    @TempDir Path tmp;

    @Test
    void scenarioPassesAgainstTheFake() throws Exception {
        try (FakeS3 fake = new FakeS3()) {
            IntegrationScenario.run(IntegrationScenario.clientConfig(fake.endpoint(), FakeS3.REGION, FakeS3.BUCKET,
                    FakeS3.ACCESS_KEY, FakeS3.SECRET_KEY, true), tmp);
            assertTrue(fake.objects.isEmpty(), "the scenario must clean up after itself: " + fake.objects.keySet());
        }
    }
}
