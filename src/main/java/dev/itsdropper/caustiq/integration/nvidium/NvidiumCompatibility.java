package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.RendererEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.function.Predicate;

/** Checks the required renderer stack; presence is not proof of pipeline compatibility. */
public final class NvidiumCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("CAUSTIQ");

    public enum Status { MISSING_SODIUM, MISSING_NVIDIUM, SODIUM_AND_NVIDIUM_PRESENT }

    private NvidiumCompatibility() { }

    /**
     * Requests Nvidium's Sodium vertex ABI before any ShaderLoader.parse call.
     * Nvidium turns this config value into USE_SODIUM_VERTEX_FORMAT. The
     * CAUSTIQ encoder must never be selected unless that shader-side ABI is
     * selected as well.
     */
    public static boolean requestExtendedVertexFormat() {
        if (!CaustiqConfig.get().enabled()) return false;
        try {
            Class<?> nvidium = Class.forName("me.cortex.nvidium.Nvidium");
            if (nvidium.getField("FORCE_DISABLE").getBoolean(null)) {
                NvidiumIrisChunkVertex.disable();
                return false;
            }
            setSodiumVertexFormat(nvidium);
            NvidiumIrisChunkVertex.enable();
            LOGGER.info("Requested CAUSTIQ's 40-byte Nvidium vertex ABI; use_sodium_vertex_format=true was set before Nvidium shader preprocessing.");
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            NvidiumIrisChunkVertex.disable();
            LOGGER.error("Could not request Nvidium's Sodium shader/vertex ABI. CAUSTIQ will leave Nvidium's native terrain format untouched.", exception);
            return false;
        }
    }

    /**
     * Commits the request after Nvidium's OpenGL capability check and before
     * RenderSectionManager creates its ChunkBuilder or NvidiumWorldRenderer.
     */
    public static boolean enableExtendedVertexFormat() {
        if (!CaustiqConfig.get().enabled() || !NvidiumIrisChunkVertex.isRequested()) {
            NvidiumIrisChunkVertex.disable();
            return false;
        }
        try {
            Class<?> nvidium = Class.forName("me.cortex.nvidium.Nvidium");
            if (!nvidium.getField("IS_COMPATIBLE").getBoolean(null)
                    || nvidium.getField("FORCE_DISABLE").getBoolean(null)) {
                NvidiumIrisChunkVertex.disable();
                LOGGER.warn("Nvidium did not pass its OpenGL capability gate; the extended CAUSTIQ vertex ABI was not committed.");
                return false;
            }
            setSodiumVertexFormat(nvidium);
            NvidiumIrisChunkVertex.commit();
            LOGGER.info("Committed CAUSTIQ Nvidium ABI {} (40-byte stride); Nvidium Sodium vertex format and shader define are enabled.",
                    NvidiumTerrainVaryingAdapter.PROFILE_ID);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            NvidiumIrisChunkVertex.disable();
            LOGGER.error("Failed to commit CAUSTIQ's Nvidium shader/vertex ABI. Nvidium will retain its native terrain format.", exception);
            return false;
        }
    }

    private static void setSodiumVertexFormat(Class<?> nvidium) throws ReflectiveOperationException {
        Object config = nvidium.getField("config").get(null);
        Field formatField = config.getClass().getField("use_sodium_vertex_format");
        formatField.setBoolean(config, true);
        if (!formatField.getBoolean(config)) {
            throw new IllegalStateException("Nvidium rejected use_sodium_vertex_format=true.");
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
