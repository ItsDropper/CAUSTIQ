package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the actual Sodium chunk view/projection matrices supplied to
 * Nvidium and uploads their Iris-compatible uniforms after Nvidium binds its
 * primary terrain program.
 */
public final class NvidiumIrisUniformBridge {
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();
    private static final AtomicReference<Snapshot> PREVIOUS = new AtomicReference<>();
    private static final AtomicInteger FRAME_COUNTER = new AtomicInteger();
    private static volatile long worldStartNanos;
    private static volatile boolean warnedUploadFailure;

    private NvidiumIrisUniformBridge() { }

    /** Called at NvidiumWorldRenderer.renderFrame HEAD, before its GPU draw phases. */
    public static void capture(Object chunkRenderMatrices, double cameraX, double cameraY, double cameraZ) {
        if (chunkRenderMatrices == null) return;
        try {
            Matrix4fc projection = (Matrix4fc) invoke(chunkRenderMatrices, "projection");
            Matrix4fc modelView = (Matrix4fc) invoke(chunkRenderMatrices, "modelView");
            Matrix4f projectionInverse = new Matrix4f(projection).invert();
            Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
            Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();
            long now = System.nanoTime();
            if (worldStartNanos == 0L) worldStartNanos = now;

            Snapshot next = new Snapshot(
                    matrix(projection),
                    matrix(modelView),
                    matrix(projectionInverse),
                    matrix(modelViewInverse),
                    matrix3(normalMatrix),
                    (float) cameraX,
                    (float) cameraY,
                    (float) cameraZ,
                    (now - worldStartNanos) / 1_000_000_000.0f,
                    FRAME_COUNTER.incrementAndGet());
            Snapshot previous = CURRENT.getAndSet(next);
            PREVIOUS.set(previous == null ? next : previous);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            CaustiqClient.LOGGER.error("Could not capture Nvidium's ChunkRenderMatrices for Iris terrain uniforms.", exception);
            CURRENT.set(null);
        }
    }

    /** Called immediately after PrimaryTerrainRasterizer binds its linked GL program. */
    public static void upload(Object rasterizer) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot == null || rasterizer == null) return;
        try {
            Field shaderField = rasterizer.getClass().getDeclaredField("shader");
            shaderField.setAccessible(true);
            Object shader = shaderField.get(rasterizer);
            Method getId = shader.getClass().getMethod("getId");
            int program = ((Number) getId.invoke(shader)).intValue();

            matrix(program, "caustiqProjection", snapshot.projection());
            matrix(program, "caustiqModelView", snapshot.modelView());
            matrix(program, "caustiqProjectionInverse", snapshot.projectionInverse());
            matrix(program, "caustiqModelViewInverse", snapshot.modelViewInverse());
            matrix3(program, "caustiqNormalMatrix", snapshot.normalMatrix());
            Snapshot previous = PREVIOUS.get();
            if (previous == null) previous = snapshot;
            matrix(program, "caustiqPreviousProjection", previous.projection());
            matrix(program, "caustiqPreviousProjectionInverse", previous.projectionInverse());
            matrix(program, "caustiqPreviousModelView", previous.modelView());
            matrix(program, "caustiqPreviousModelViewInverse", previous.modelViewInverse());
            vector3(program, "caustiqPreviousCameraPosition",
                    previous.cameraX(), previous.cameraY(), previous.cameraZ());
            if (NvidiumShadowRenderer.isShadowRasterization() || NvidiumShadowRenderer.isAvailable()) {
                uploadShadowMatrices(program);
            }
            vector3(program, "caustiqCameraPosition",
                    snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
            int timeLocation = GL20C.glGetUniformLocation(program, "caustiqFrameTimeCounter");
            if (timeLocation >= 0) GL20C.glUniform1f(timeLocation, snapshot.frameTimeCounter());
            int frameLocation = GL20C.glGetUniformLocation(program, "caustiqFrameCounter");
            if (frameLocation >= 0) GL20C.glUniform1i(frameLocation, snapshot.frameCounter());
            uploadPhotonUniforms(program, snapshot);
            if (NvidiumShadowRenderer.isShadowRasterization()) {
                NvidiumIrisTextureBridge.bindForShadow(program);
            } else {
                NvidiumIrisTextureBridge.bindForTerrain();
                int noiseLocation = GL20C.glGetUniformLocation(program, "noisetex");
                if (noiseLocation >= 0) GL20C.glUniform1i(noiseLocation, 2);
            }
            ShaderRuntime.uploadTerrainCustomUniforms(program);
            ShaderRuntime.bindTerrainCustomTextures(program);
            warnedUploadFailure = false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedUploadFailure) {
                warnedUploadFailure = true;
                CaustiqClient.LOGGER.warn("Unable to upload Iris terrain matrices to Nvidium's primary shader.", exception);
            }
        }
    }

    private static void uploadShadowMatrices(int program) {
        float[] shadowView = NvidiumShadowRenderer.shadowModelView();
        float[] shadowProjection = NvidiumShadowRenderer.shadowProjection();
        matrix(program, "caustiqShadowModelView", shadowView);
        matrix(program, "caustiqShadowProjection", shadowProjection);
        matrix(program, "caustiqShadowModelViewInverse",
                new org.joml.Matrix4f().set(shadowView).invert().get(new float[16]));
        matrix(program, "caustiqShadowProjectionInverse",
                new org.joml.Matrix4f().set(shadowProjection).invert().get(new float[16]));
    }

    private static void uploadPhotonUniforms(int program, Snapshot snapshot) {
        float near = projectionDistance(snapshot.projection(), false);
        float far = projectionDistance(snapshot.projection(), true);
        Object level = Minecraft.getInstance().level;
        float rain = invokeFloat(level, "getRainLevel", 1.0f, 0.0f);
        float wetness = rain;
        float worldAge = invokeLong(level, "getGameTime", 0L);
        int worldTime = (int) Math.floorMod(invokeLong(level, "getDayTime", 0L), 24_000L);
        float dayProgress = worldTime / 24_000.0f;
        float sunAngle = invokeFloat(level, "getSunAngle", 1.0f, 0.0f);
        var window = Minecraft.getInstance().getWindow();
        float aspectRatio = window.getHeight() > 0
                ? (float) window.getWidth() / (float) window.getHeight() : 1.0f;

        scalar(program, "caustiqNear", near);
        scalar(program, "caustiqFar", far);
        scalar(program, "caustiqAspectRatio", aspectRatio);
        int[] atlasSize = blockAtlasSize();
        integer2(program, "caustiqAtlasSize", atlasSize[0], atlasSize[1]);
        scalar(program, "caustiqRainStrength", rain);
        scalar(program, "caustiqWetness", wetness);
        vector2(program, "caustiqTaaOffset", 0.0f, 0.0f);
        vector3(program, "caustiqLightDirection",
                (float) Math.sin(sunAngle), (float) Math.cos(sunAngle), 0.0f);
        scalar(program, "caustiqWorldAge", worldAge);
        integer(program, "caustiqWorldTime", worldTime);
        integer(program, "caustiqWorldDay", (int) Math.floorDiv(invokeLong(level, "getDayTime", 0L), 24_000L));
        scalar(program, "caustiqDayFactor", 0.5f + 0.5f * (float) Math.cos((dayProgress - 0.25f) * Math.PI * 2.0));
        float biomeTemperature = biomeFloat(level, "getBaseTemperature");
        float biomeHumidity = biomeFloat(level, "getDownfall");
        if (Float.isFinite(biomeTemperature)) scalar(program, "caustiqBiomeTemperature", biomeTemperature);
        if (Float.isFinite(biomeHumidity)) scalar(program, "caustiqBiomeHumidity", biomeHumidity);
        scalar(program, "caustiqTimeSunrise", dayPhaseWeight(dayProgress, 0.0f));
        scalar(program, "caustiqTimeNoon", dayPhaseWeight(dayProgress, 0.25f));
        scalar(program, "caustiqTimeSunset", dayPhaseWeight(dayProgress, 0.5f));
        scalar(program, "caustiqTimeMidnight", dayPhaseWeight(dayProgress, 0.75f));
        scalar(program, "caustiqAlphaTestRef", 0.1f);
        integer(program, "caustiqBlockEntityId", 0);
        integer(program, "caustiqEntityId", 0);
        integer(program, "caustiqCurrentRenderedItemId", 0);
        vector4(program, "caustiqEntityColor", 1.0f, 1.0f, 1.0f, 1.0f);
        integer(program, "caustiqNoiseTexture", 2);
        integer(program, "caustiqHeavyFog", isHeavyFog() ? 1 : 0);
        integer(program, "caustiqIsElytraFlying", isElytraFlying() ? 1 : 0);
        integer(program, "caustiqIsEyeInWater", isEyeInWater());
        integer(program, "caustiqMoonPhase", moonPhase(level));
        integer(program, "caustiqHeldBlockLightValue", heldBlockLightValue(InteractionHand.MAIN_HAND));
        integer(program, "caustiqHeldBlockLightValue2", heldBlockLightValue(InteractionHand.OFF_HAND));
        integer(program, "caustiqHeldItemId", heldItemId(InteractionHand.MAIN_HAND));
        integer(program, "caustiqHeldItemId2", heldItemId(InteractionHand.OFF_HAND));
        integer(program, "caustiqColortex1", 5);
        integer(program, "caustiqColortex2", 6);
        integer(program, "caustiqColortex4", 7);
    }

    /** Returns the registry ID of the selected hand's item, matching Iris heldItemId uniforms. */
    private static int heldItemId(InteractionHand hand) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        ItemStack stack = player.getItemInHand(hand);
        return stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem());
    }

    private static int[] blockAtlasSize() {
        try {
            Object atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            int width = readIntegerField(atlas, "width");
            int height = readIntegerField(atlas, "height");
            if (width > 0 && height > 0) return new int[]{width, height};
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep a finite fallback while the block atlas is being rebuilt.
        }
        return new int[]{1024, 1024};
    }

    private static int readIntegerField(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof Number number) return number.intValue();
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
                continue;
            }
            break;
        }
        throw new NoSuchFieldException(name);
    }

    /** Mirrors Minecraft's eight-step lunar phase, derived from the world's day time. */
    private static int moonPhase(Object level) {
        long dayTime = invokeLong(level, "getDayTime", 0L);
        return (int) Math.floorMod(Math.floorDiv(dayTime, 24_000L), 8L);
    }

    /** Mirrors Iris isEyeInWater(): 0=none, 1=water, 2=lava, 3=powder snow. */
    private static int isEyeInWater() {
        try {
            Object camera = invoke(Minecraft.getInstance().gameRenderer, "getMainCamera");
            Object fluid = invoke(camera, "getFluidInCamera");
            if (!(fluid instanceof Enum<?> fogType)) return 0;
            return switch (fogType.name()) {
                case "WATER" -> 1;
                case "LAVA" -> 2;
                case "POWDER_SNOW" -> 3;
                default -> 0;
            };
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    /** Mirrors Iris IrisExclusiveUniforms.isElytraFlying(): the local player's fall-flying state. */
    private static boolean isElytraFlying() {
        return invokeBoolean(Minecraft.getInstance().player, "isFallFlying", false);
    }

    /**
     * Mirrors Iris IdMapUniforms' main-hand heldBlockLightValue supplier.
     * Iris adds getLightEmission(Player, ItemStack) to items through its item-light
     * provider interface; reflection keeps CAUSTIQ independent of an Iris runtime dependency.
     */
    private static int heldBlockLightValue(InteractionHand hand) {
        Object player = Minecraft.getInstance().player;
        if (player == null) return 0;
        try {
            Object stack = player.getClass()
                    .getMethod("getItemInHand", InteractionHand.class)
                    .invoke(player, hand);
            if (!(stack instanceof ItemStack itemStack)) return 0;
            Object item = itemStack.getItem();
            if (item == null) return 0;
            for (Method method : item.getClass().getMethods()) {
                if (method.getName().equals("getLightEmission") && method.getParameterCount() == 2) {
                    Object value = method.invoke(item, player, itemStack);
                    if (value instanceof Number number) return Math.max(0, number.intValue());
                }
            }
            if (item instanceof BlockItem blockItem) {
                var state = blockItem.getBlock().defaultBlockState();
                BlockItemStateProperties properties = itemStack.getComponents().get(DataComponents.BLOCK_STATE);
                if (properties != null) state = properties.apply(state);
                return state.getLightEmission();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Without Iris' item-light provider, its documented default is no held-item light.
        }
        return 0;
    }

    /** Mirrors Iris IrisExclusiveUniforms.isHeavyFog(): boss-overlay world-fog request. */
    private static boolean isHeavyFog() {
        return Minecraft.getInstance().level != null
                && Minecraft.getInstance().gui.getBossOverlay().shouldCreateWorldFog();
    }

    /** Smooth, clock-derived weights peaking at Minecraft dawn, noon, dusk and midnight. */
    private static float dayPhaseWeight(float phase, float peak) {
        return Math.max(0.0f, (float) Math.cos((phase - peak) * Math.PI * 2.0));
    }

    private static float biomeFloat(Object level, String methodName) {
        if (level == null || Minecraft.getInstance().player == null) return Float.NaN;
        try {
            Object player = Minecraft.getInstance().player;
            Object position = player.getClass().getMethod("blockPosition").invoke(player);
            Object holder = level.getClass().getMethod("getBiome", position.getClass()).invoke(level, position);
            Object biome = holder.getClass().getMethod("value").invoke(holder);
            Object value = biome.getClass().getMethod(methodName).invoke(biome);
            return value instanceof Number number ? number.floatValue() : Float.NaN;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Float.NaN;
        }
    }

    private static float projectionDistance(float[] projection, boolean farPlane) {
        float diagonal = projection[10];
        float translation = projection[14];
        float denominator = diagonal + (farPlane ? 1.0f : -1.0f);
        if (!Float.isFinite(denominator) || Math.abs(denominator) < 1.0e-6f) {
            return farPlane ? 512.0f : 0.05f;
        }
        float distance = Math.abs(translation / denominator);
        return Float.isFinite(distance) && distance > 0.0f
                ? distance : (farPlane ? 512.0f : 0.05f);
    }

    private static float invokeFloat(Object target, String name, float argument, float fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(name, float.class).invoke(target, argument);
            return value instanceof Number number ? number.floatValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static double invokeDouble(Object target, String name, double fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value instanceof Number number ? number.doubleValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean invokeBoolean(Object target, String name, boolean fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value instanceof Boolean result ? result : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static long invokeLong(Object target, String name, long fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value instanceof Number number ? number.longValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static void scalar(int program, String name, float value) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1f(location, value);
    }

    private static void integer(int program, String name, int value) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1i(location, value);
    }

    private static void integer2(int program, String name, int x, int y) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform2i(location, x, y);
    }

    private static void vector2(int program, String name, float x, float y) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform2f(location, x, y);
    }

    private static void vector4(int program, String name, float x, float y, float z, float w) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform4f(location, x, y, z, w);
    }

    /** Uploads the captured camera/time state to a CAUSTIQ OpenGL post-process program. */
    public static void clearShadowVoxelImageForCurrentFrame() {
        Snapshot snapshot = CURRENT.get();
        if (snapshot != null) ShaderRuntime.prepareShadowVoxelImage(snapshot.frameCounter());
    }

    public static void uploadProgram(int program) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot == null || program == 0) return;
        matrix(program, "gbufferProjection", snapshot.projection());
        matrix(program, "gbufferModelView", snapshot.modelView());
        matrix(program, "gbufferProjectionInverse", snapshot.projectionInverse());
        matrix(program, "gbufferModelViewInverse", snapshot.modelViewInverse());
        matrix(program, "gl_ProjectionMatrix", snapshot.projection());
        matrix(program, "gl_ModelViewMatrix", snapshot.modelView());
        matrix(program, "gl_ProjectionMatrixInverse", snapshot.projectionInverse());
        matrix(program, "gl_ModelViewMatrixInverse", snapshot.modelViewInverse());
        matrix3(program, "gl_NormalMatrix", snapshot.normalMatrix());
        if (NvidiumShadowRenderer.isShadowRasterization() || NvidiumShadowRenderer.isAvailable()) {
            uploadShadowMatrices(program);
        }
        vector3(program, "cameraPosition", snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
        Snapshot previous = PREVIOUS.get() == null ? snapshot : PREVIOUS.get();
        matrix(program, "gbufferPreviousModelView", previous.modelView());
        vector3(program, "previousCameraPosition",
                previous.cameraX(), previous.cameraY(), previous.cameraZ());
        int timeLocation = GL20C.glGetUniformLocation(program, "frameTimeCounter");
        if (timeLocation >= 0) GL20C.glUniform1f(timeLocation, snapshot.frameTimeCounter());
        int frameLocation = GL20C.glGetUniformLocation(program, "frameCounter");
        if (frameLocation >= 0) GL20C.glUniform1i(frameLocation, snapshot.frameCounter());
    }

    /** Current frame values exposed to Iris custom uniform/variable expressions. */
    public static java.util.Map<String, Double> expressionVariables(int viewWidth, int viewHeight) {
        java.util.Map<String, Double> values = new java.util.HashMap<>();
        Snapshot snapshot = CURRENT.get();
        Object level = Minecraft.getInstance().level;
        long worldAge = invokeLong(level, "getGameTime", 0L);
        long dayTime = invokeLong(level, "getDayTime", 0L);
        float rain = invokeFloat(level, "getRainLevel", 1.0f, 0.0f);
        float sunAngle = invokeFloat(level, "getSunAngle", 1.0f, 0.0f);
        values.put("frameCounter", snapshot == null ? 0.0 : (double) snapshot.frameCounter());
        values.put("frameTimeCounter", snapshot == null ? 0.0 : (double) snapshot.frameTimeCounter());
        values.put("worldAge", (double) worldAge);
        values.put("worldDay", (double) Math.floorDiv(dayTime, 24_000L));
        values.put("worldTime", (double) Math.floorMod(dayTime, 24_000L));
        values.put("rainStrength", (double) rain);
        values.put("wetness", (double) rain);
        values.put("sunAngle", (double) sunAngle);
        values.put("viewWidth", (double) viewWidth);
        values.put("viewHeight", (double) viewHeight);
        values.put("aspectRatio", viewHeight == 0 ? 1.0 : (double) viewWidth / viewHeight);
        values.put("viewSize.x", (double) viewWidth);
        values.put("viewSize.y", (double) viewHeight);
        values.put("isNight", Math.floorMod(dayTime, 24_000L) >= 13_000L ? 1.0 : 0.0);
        values.put("isDay", Math.floorMod(dayTime, 24_000L) < 13_000L ? 1.0 : 0.0);
        values.put("isRaining", invokeBoolean(level, "isRaining", false) ? 1.0 : 0.0);
        values.put("isThundering", invokeBoolean(level, "isThundering", false) ? 1.0 : 0.0);
        Object player = Minecraft.getInstance().player;
        values.put("eyeAltitude", invokeDouble(player, "getEyeY", 0.0));
        values.put("health", invokeDouble(player, "getHealth", 20.0));
        values.put("maxHealth", invokeDouble(player, "getMaxHealth", 20.0));
        values.put("isSneaking", invokeBoolean(player, "isShiftKeyDown", false) ? 1.0 : 0.0);
        values.put("isOnGround", invokeBoolean(player, "onGround", false) ? 1.0 : 0.0);
        values.put("isInWater", invokeBoolean(player, "isInWater", false) ? 1.0 : 0.0);
        values.put("isInLava", invokeBoolean(player, "isInLava", false) ? 1.0 : 0.0);
        values.put("isSprinting", invokeBoolean(player, "isSprinting", false) ? 1.0 : 0.0);
        if (snapshot != null) {
            putMatrixComponents(values, "gbufferProjection", snapshot.projection(), 4);
            putMatrixComponents(values, "gbufferModelView", snapshot.modelView(), 4);
            putMatrixComponents(values, "gbufferProjectionInverse", snapshot.projectionInverse(), 4);
            putMatrixComponents(values, "gbufferModelViewInverse", snapshot.modelViewInverse(), 4);
            putMatrixComponents(values, "gl_ProjectionMatrix", snapshot.projection(), 4);
            putMatrixComponents(values, "gl_ModelViewMatrix", snapshot.modelView(), 4);
            putMatrixComponents(values, "gl_ProjectionMatrixInverse", snapshot.projectionInverse(), 4);
            putMatrixComponents(values, "gl_ModelViewMatrixInverse", snapshot.modelViewInverse(), 4);
            Snapshot previous = PREVIOUS.get() == null ? snapshot : PREVIOUS.get();
            putMatrixComponents(values, "gbufferPreviousModelView", previous.modelView(), 4);
            putMatrixComponents(values, "gbufferPreviousModelViewInverse", previous.modelViewInverse(), 4);
            putMatrixComponents(values, "gbufferPreviousProjection", previous.projection(), 4);
            putMatrixComponents(values, "gbufferPreviousProjectionInverse", previous.projectionInverse(), 4);
            values.put("cameraPositionX", (double) snapshot.cameraX());
            values.put("cameraPositionY", (double) snapshot.cameraY());
            values.put("cameraPositionZ", (double) snapshot.cameraZ());
            values.put("cameraPosition.x", (double) snapshot.cameraX());
            values.put("cameraPosition.y", (double) snapshot.cameraY());
            values.put("cameraPosition.z", (double) snapshot.cameraZ());
            values.put("near", (double) projectionDistance(snapshot.projection(), false));
            values.put("far", (double) projectionDistance(snapshot.projection(), true));
        }
        return java.util.Map.copyOf(values);
    }

    static void putMatrixComponents(java.util.Map<String, Double> values,
                                    String name, float[] matrix, int dimension) {
        for (int column = 0; column < dimension; column++) {
            for (int row = 0; row < dimension; row++) {
                values.put(name + "." + column + "." + row,
                        (double) matrix[column * dimension + row]);
            }
        }
    }

    static Snapshot snapshot() { return CURRENT.get(); }

    static void resetForTests() {
        CURRENT.set(null);
        PREVIOUS.set(null);
        FRAME_COUNTER.set(0);
        worldStartNanos = 0L;
        warnedUploadFailure = false;
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static float[] matrix(Matrix4fc matrix) {
        return matrix.get(new float[16]);
    }

    private static float[] matrix3(Matrix3fc matrix) {
        return matrix.get(new float[9]);
    }

    private static void matrix(int program, String name, float[] values) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniformMatrix4fv(location, false, values);
    }

    private static void matrix3(int program, String name, float[] values) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniformMatrix3fv(location, false, values);
    }

    private static void vector3(int program, String name, float x, float y, float z) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform3f(location, x, y, z);
    }

    record Snapshot(
            float[] projection,
            float[] modelView,
            float[] projectionInverse,
            float[] modelViewInverse,
            float[] normalMatrix,
            float cameraX,
            float cameraY,
            float cameraZ,
            float frameTimeCounter,
            int frameCounter) {
        Snapshot {
            projection = projection.clone();
            modelView = modelView.clone();
            projectionInverse = projectionInverse.clone();
            modelViewInverse = modelViewInverse.clone();
            normalMatrix = normalMatrix.clone();
        }
    }
}
