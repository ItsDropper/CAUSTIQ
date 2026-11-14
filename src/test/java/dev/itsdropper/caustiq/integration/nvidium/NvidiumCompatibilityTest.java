package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumCompatibilityTest {
    @Test void requiresSodium() {
        assertEquals(NvidiumCompatibility.Status.MISSING_SODIUM, NvidiumCompatibility.inspect(Set.of("nvidium")::contains));
    }
    @Test void requiresNvidiumForThisDistribution() {
        assertEquals(NvidiumCompatibility.Status.MISSING_NVIDIUM, NvidiumCompatibility.inspect(Set.of("sodium")::contains));
    }
    @Test void acceptsExpectedStackWithoutClaimingPipelineSupport() {
        assertEquals(NvidiumCompatibility.Status.SODIUM_AND_NVIDIUM_PRESENT, NvidiumCompatibility.inspect(Set.of("sodium", "nvidium")::contains));
    }
}
