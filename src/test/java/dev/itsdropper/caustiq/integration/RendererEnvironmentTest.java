package dev.itsdropper.caustiq.integration;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererEnvironmentTest {
    @Test
    void detectsVanillaWithoutOptionalRendererMods() {
        assertEquals(RendererEnvironment.Stack.VANILLA,
                RendererEnvironment.detect(Set.<String>of()::contains).stack());
    }

    @Test
    void detectsSodiumWithoutRequiringNvidium() {
        assertEquals(RendererEnvironment.Stack.SODIUM,
                RendererEnvironment.detect(Set.of("sodium")::contains).stack());
    }

    @Test
    void detectsTheCombinedSodiumAndNvidiumStack() {
        assertEquals(RendererEnvironment.Stack.SODIUM_WITH_NVIDIUM,
                RendererEnvironment.detect(Set.of("sodium", "nvidium")::contains).stack());
    }

    @Test
    void identifiesAnInvalidNvidiumStackWithoutSodium() {
        assertEquals(RendererEnvironment.Stack.INVALID_NVIDIUM_WITHOUT_SODIUM,
                RendererEnvironment.detect(Set.of("nvidium")::contains).stack());
    }
}
