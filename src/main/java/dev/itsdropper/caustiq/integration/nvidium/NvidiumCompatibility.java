package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.integration.RendererEnvironment;
import java.util.Objects;
import java.util.function.Predicate;

/** Checks the required renderer stack; presence is not proof of pipeline compatibility. */
public final class NvidiumCompatibility {
    public enum Status { MISSING_SODIUM, MISSING_NVIDIUM, SODIUM_AND_NVIDIUM_PRESENT }
    private NvidiumCompatibility() { }
    public static Status inspect(Predicate<String> isModLoaded) {
        Objects.requireNonNull(isModLoaded, "isModLoaded");
        RendererEnvironment environment = RendererEnvironment.detect(isModLoaded);
        if (!environment.sodiumLoaded()) return Status.MISSING_SODIUM;
        if (!environment.nvidiumLoaded()) return Status.MISSING_NVIDIUM;
        return Status.SODIUM_AND_NVIDIUM_PRESENT;
    }
}
