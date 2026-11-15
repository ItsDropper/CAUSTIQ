package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.RendererEnvironment;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.function.Predicate;

/** Checks the required renderer stack; presence is not proof of pipeline compatibility. */
public final class NvidiumCompatibility {
    public enum Status { MISSING_SODIUM, MISSING_NVIDIUM, SODIUM_AND_NVIDIUM_PRESENT }
    private NvidiumCompatibility() { }
    /**
     * Nvidium's native compact format has no room for Iris terrain attributes.
     * Force its Sodium-compatible base format before Sodium creates the chunk
     * builder; CAUSTIQ then supplies a stride-extended ChunkVertexType.
     */
    public static boolean enableExtendedVertexFormat() {
        if (!CaustiqConfig.get().enabled()) return false;
        try {
            Class<?> nvidium = Class.forName("me.cortex.nvidium.Nvidium");
            if (!nvidium.getField("IS_COMPATIBLE").getBoolean(null)
                    || nvidium.getField("FORCE_DISABLE").getBoolean(null)) {
                return false;
            }
            Field configField = nvidium.getField("config");
            Object config = configField.get(null);
            Field formatField = config.getClass().getField("use_sodium_vertex_format");
            formatField.setBoolean(config, true);
            NvidiumIrisChunkVertex.enable();
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    public static Status inspect(Predicate<String> isModLoaded) {
        Objects.requireNonNull(isModLoaded, "isModLoaded");
        RendererEnvironment environment = RendererEnvironment.detect(isModLoaded);
        if (!environment.sodiumLoaded()) return Status.MISSING_SODIUM;
        if (!environment.nvidiumLoaded()) return Status.MISSING_NVIDIUM;
        return Status.SODIUM_AND_NVIDIUM_PRESENT;
    }
}
