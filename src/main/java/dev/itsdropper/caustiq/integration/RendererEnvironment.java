package dev.itsdropper.caustiq.integration;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Describes the renderer mods present without linking CAUSTIQ's common code to
 * Sodium or Nvidium implementation classes.
 */
public record RendererEnvironment(boolean sodiumLoaded, boolean nvidiumLoaded) {
    public enum Stack {
        VANILLA,
        SODIUM,
        SODIUM_WITH_NVIDIUM,
        INVALID_NVIDIUM_WITHOUT_SODIUM
    }

    public static RendererEnvironment detect(Predicate<String> isModLoaded) {
        Objects.requireNonNull(isModLoaded, "isModLoaded");
        return new RendererEnvironment(isModLoaded.test("sodium"), isModLoaded.test("nvidium"));
    }

    public Stack stack() {
        if (nvidiumLoaded && !sodiumLoaded) return Stack.INVALID_NVIDIUM_WITHOUT_SODIUM;
        if (nvidiumLoaded) return Stack.SODIUM_WITH_NVIDIUM;
        if (sodiumLoaded) return Stack.SODIUM;
        return Stack.VANILLA;
    }
}
