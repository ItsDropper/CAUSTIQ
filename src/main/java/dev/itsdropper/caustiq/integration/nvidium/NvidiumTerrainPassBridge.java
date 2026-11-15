package dev.itsdropper.caustiq.integration.nvidium;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Reads Sodium's active terrain layer without linking CAUSTIQ to a specific Sodium implementation class. */
public final class NvidiumTerrainPassBridge {
    private NvidiumTerrainPassBridge() { }

    /** Clear the pack's MRT attachments on the first opaque layer; preserve them for cutout layers. */
    public static boolean shouldClearAttachments(Object terrainPass) {
        if (terrainPass == null) return true;
        Object renderType = readRenderType(terrainPass);
        if (renderType == null) return true;
        String name = renderType.toString().toUpperCase(Locale.ROOT);
        return name.equals("SOLID") || name.equals("OPAQUE");
    }

    /** True for Nvidium/Sodium cutout terrain layers, including cutout-mipped. */
    public static boolean isCutoutPass(Object terrainPass) {
        Object renderType = readRenderType(terrainPass);
        return renderType != null
                && renderType.toString().toUpperCase(Locale.ROOT).contains("CUTOUT");
    }

    private static Object readRenderType(Object terrainPass) {
        try {
            Method accessor = terrainPass.getClass().getMethod("renderType");
            return accessor.invoke(terrainPass);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            try {
                Field field = terrainPass.getClass().getDeclaredField("renderType");
                field.setAccessible(true);
                return field.get(terrainPass);
            } catch (ReflectiveOperationException | RuntimeException ignoredAgain) {
                return null;
            }
        }
    }
}
