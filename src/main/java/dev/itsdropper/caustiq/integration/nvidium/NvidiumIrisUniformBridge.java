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
        integer(program, "caustiqShadowtex0", 12);
        integer(program, "caustiqShadowtex1", 13);
        integer(program, "caustiqShadowcolor0", 14);
        integer(program, "caustiqShadowcolor1", 15);
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
        putIrisBuiltInDefaults(values);

        Minecraft client = Minecraft.getInstance();
        Object player = client.player;
        Object level = client.level;
        Snapshot snapshot = CURRENT.get();
        Snapshot previous = PREVIOUS.get() == null ? snapshot : PREVIOUS.get();
        long worldAge = invokeLong(level, "getGameTime", 0L);
        long dayTime = invokeLong(level, "getDayTime", 0L);
        long timeOfDay = Math.floorMod(dayTime - 6_000L, 24_000L);
        float normalizedSunAngle = timeOfDay / 24_000.0f;
        float celestialAngle = invokeFloat(level, "getSunAngle", 1.0f,
                (float) (normalizedSunAngle * Math.PI * 2.0));
        float rain = invokeFloat(level, "getRainLevel", 1.0f, 0.0f);
        float thunder = invokeFloat(level, "getThunderLevel", 1.0f, 0.0f);
        float near = snapshot == null ? 0.05f : projectionDistance(snapshot.projection(), false);
        float far = snapshot == null ? 512.0f : projectionDistance(snapshot.projection(), true);

        putScalar(values, "frameCounter", snapshot == null ? FRAME_COUNTER.get() : snapshot.frameCounter());
        putScalar(values, "frameTime", 1.0 / 60.0);
        putScalar(values, "frameTimeCounter", snapshot == null ? 0.0 : snapshot.frameTimeCounter());
        putScalar(values, "worldAge", worldAge);
        putScalar(values, "worldDay", Math.floorDiv(dayTime, 24_000L));
        putScalar(values, "worldTime", Math.floorMod(dayTime, 24_000L));
        putScalar(values, "sunAngle", normalizedSunAngle);
        putScalar(values, "shadowAngle", normalizedSunAngle <= 0.5f
                ? normalizedSunAngle : normalizedSunAngle - 0.5f);
        putScalar(values, "rainStrength", rain);
        putScalar(values, "wetness", rain);
        putScalar(values, "thunderStrength", thunder);
        putScalar(values, "moonPhase", moonPhase(level));
        putScalar(values, "near", near);
        putScalar(values, "far", far);
        putScalar(values, "dhNearPlane", near);
        putScalar(values, "dhFarPlane", far);
        putScalar(values, "dhRenderDistance", far);
        putScalar(values, "viewWidth", viewWidth);
        putScalar(values, "viewHeight", viewHeight);
        putScalar(values, "aspectRatio", viewHeight <= 0 ? 1.0 : (double) viewWidth / viewHeight);
        putVector2(values, "viewSize", viewWidth, viewHeight);
        putVector2(values, "iris_ScreenSize", viewWidth, viewHeight);
        putVector2(values, "iris_ScreenSize", viewWidth, viewHeight);
        putScalar(values, "eyeAltitude", invokeDouble(player, "getEyeY",
                snapshot == null ? 0.0 : snapshot.cameraY()));
        putScalar(values, "screenBrightness", optionDouble(client, "gamma", 0.5));
        putScalar(values, "isNight", timeOfDay >= 13_000L ? 1.0 : 0.0);
        putScalar(values, "isDay", timeOfDay < 13_000L ? 1.0 : 0.0);
        putScalar(values, "isRaining", invokeBoolean(level, "isRaining", false) ? 1.0 : 0.0);
        putScalar(values, "isThundering", invokeBoolean(level, "isThundering", false) ? 1.0 : 0.0);
        putScalar(values, "isEyeInWater", isEyeInWater());
        putScalar(values, "isSpectator", invokeBoolean(player, "isSpectator", false) ? 1.0 : 0.0);
        putScalar(values, "isRightHanded", isRightHanded(client) ? 1.0 : 0.0);
        putScalar(values, "firstPersonCamera", isFirstPerson(client) ? 1.0 : 0.0);
        putScalar(values, "hideGUI", optionBoolean(client, "hideGui", false) ? 1.0 : 0.0);
        putScalar(values, "is_sneaking", invokeBoolean(player, "isShiftKeyDown", false) ? 1.0 : 0.0);
        putScalar(values, "is_sprinting", invokeBoolean(player, "isSprinting", false) ? 1.0 : 0.0);
        putScalar(values, "is_on_ground", invokeBoolean(player, "onGround", false) ? 1.0 : 0.0);
        putScalar(values, "is_burning", invokeBoolean(player, "isOnFire", false) ? 1.0 : 0.0);
        putScalar(values, "is_hurt", invokeBoolean(player, "isHurt", false) ? 1.0 : 0.0);
        putScalar(values, "is_invisible", invokeBoolean(player, "isInvisible", false) ? 1.0 : 0.0);
        putScalar(values, "isElytraFlying", isElytraFlying() ? 1.0 : 0.0);
        putScalar(values, "isRiding", invokeBoolean(player, "isPassenger", false) ? 1.0 : 0.0);
        putScalar(values, "feetInWater", invokeBoolean(player, "isInWater", false) ? 1.0 : 0.0);
        putScalar(values, "vehicleInWater", vehicleInWater(player) ? 1.0 : 0.0);

        double health = invokeDouble(player, "getHealth", 20.0);
        double maxHealth = invokeDouble(player, "getMaxHealth", 20.0);
        double armor = invokeDouble(player, "getArmorValue", 0.0);
        double hunger = invokeDouble(invokeObject(player, "getFoodData"), "getFoodLevel", 20.0);
        double air = invokeDouble(player, "getAirSupply", 300.0);
        double maxAir = invokeDouble(player, "getMaxAirSupply", 300.0);
        putScalar(values, "currentPlayerHealth", maxHealth <= 0 ? 0 : health / maxHealth);
        putScalar(values, "maxPlayerHealth", maxHealth);
        putScalar(values, "currentPlayerArmor", armor / 20.0);
        putScalar(values, "maxPlayerArmor", 20.0);
        putScalar(values, "currentPlayerHunger", hunger / 20.0);
        putScalar(values, "maxPlayerHunger", 20.0);
        putScalar(values, "currentPlayerAir", maxAir <= 0 ? 0 : air / maxAir);
        putScalar(values, "maxPlayerAir", maxAir);
        putScalar(values, "health", health);
        putScalar(values, "maxHealth", maxHealth);
        putScalar(values, "isInWater", invokeBoolean(player, "isInWater", false) ? 1.0 : 0.0);
        putScalar(values, "isInLava", invokeBoolean(player, "isInLava", false) ? 1.0 : 0.0);

        Object biome = currentBiome(level, player);
        putBiomeUniforms(values, biomeFloat(level, "getBaseTemperature"),
                biomeFloat(level, "getDownfall"));
        putBiomeIdentity(values, biome);
        putDimensionUniforms(values, level);
        putVector2(values, "eyeBrightness", eyeBrightness(level, player, false),
                eyeBrightness(level, player, true));
        putVector2(values, "eyeBrightnessSmooth", eyeBrightness(level, player, false),
                eyeBrightness(level, player, true));
        putScalar(values, "blindness", effectPresent(player, "blindness") ? 1.0 : 0.0);
        putScalar(values, "darknessFactor", effectPresent(player, "darkness") ? 1.0 : 0.0);
        putScalar(values, "darknessLightFactor", effectPresent(player, "darkness") ? 1.0 : 0.0);
        putScalar(values, "nightVision", effectPresent(player, "night_vision") ? 1.0 : 0.0);
        putScalar(values, "playerMood", 0.0);
        putScalar(values, "constantMood", 0.0);

        int[] atlas = blockAtlasSize();
        putVector2(values, "atlasSize", atlas[0], atlas[1]);
        putVector2(values, "gtextureSize", atlas[0], atlas[1]);
        putScalar(values, "heldBlockLightValue", heldBlockLightValue(InteractionHand.MAIN_HAND));
        putScalar(values, "heldBlockLightValue2", heldBlockLightValue(InteractionHand.OFF_HAND));
        putScalar(values, "heldItemId", heldItemId(InteractionHand.MAIN_HAND));
        putScalar(values, "heldItemId2", heldItemId(InteractionHand.OFF_HAND));
        putScalar(values, "entityId", -1);
        putScalar(values, "blockEntityId", -1);
        putScalar(values, "currentRenderedItemId", -1);
        putScalar(values, "vehicleId", 0);
        putScalar(values, "currentSelectedBlockId", 0);
        putScalar(values, "textureReloadCount", 0);
        putScalar(values, "textureFilteringMode", 0);
        putScalar(values, "renderStage", 0);
        putScalar(values, "alphaTestRef", 0.1);
        putScalar(values, "fogMode", 0);
        putScalar(values, "fogShape", -1);
        putScalar(values, "fogDensity", 0.0);
        putScalar(values, "fogStart", 0.0);
        putScalar(values, "fogEnd", far);
        putScalar(values, "centerDepthSmooth", 0.0);
        putScalar(values, "pi", Math.PI);
        putScalar(values, "currentColorSpace", 0);
        putScalar(values, "anisotropicFiltering", 0);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        putVector3(values, "currentDate", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
        putVector3(values, "currentTime", now.getHour(), now.getMinute(), now.getSecond());
        java.time.LocalDateTime yearStart = now.withDayOfYear(1).toLocalDate().atStartOfDay();
        java.time.LocalDateTime yearEnd = yearStart.plusYears(1);
        long elapsed = java.time.Duration.between(yearStart, now).getSeconds();
        long remaining = java.time.Duration.between(now, yearEnd).getSeconds();
        putVector2(values, "currentYearTime", elapsed, remaining);

        if (snapshot != null) {
            putVector3(values, "cameraPosition", snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
            putVector3(values, "cameraPositionInt", Math.floor(snapshot.cameraX()),
                    Math.floor(snapshot.cameraY()), Math.floor(snapshot.cameraZ()));
            putVector3(values, "cameraPositionFract", fract(snapshot.cameraX()),
                    fract(snapshot.cameraY()), fract(snapshot.cameraZ()));
            putVector3(values, "previousCameraPosition", previous.cameraX(), previous.cameraY(), previous.cameraZ());
            putVector3(values, "previousCameraPositionInt", Math.floor(previous.cameraX()),
                    Math.floor(previous.cameraY()), Math.floor(previous.cameraZ()));
            putVector3(values, "previousCameraPositionFract", fract(previous.cameraX()),
                    fract(previous.cameraY()), fract(previous.cameraZ()));
            putMatrixComponents(values, "gbufferProjection", snapshot.projection(), 4);
            putMatrixComponents(values, "gbufferModelView", snapshot.modelView(), 4);
            putMatrixComponents(values, "gbufferProjectionInverse", snapshot.projectionInverse(), 4);
            putMatrixComponents(values, "gbufferModelViewInverse", snapshot.modelViewInverse(), 4);
            putMatrixComponents(values, "gl_ProjectionMatrix", snapshot.projection(), 4);
            putMatrixComponents(values, "gl_ModelViewMatrix", snapshot.modelView(), 4);
            putMatrixComponents(values, "gl_ProjectionMatrixInverse", snapshot.projectionInverse(), 4);
            putMatrixComponents(values, "gl_ModelViewMatrixInverse", snapshot.modelViewInverse(), 4);
            putMatrixComponents(values, "gbufferPreviousModelView", previous.modelView(), 4);
            putMatrixComponents(values, "gbufferPreviousProjection", previous.projection(), 4);
            putMatrixComponents(values, "gbufferPreviousModelViewInverse", previous.modelViewInverse(), 4);
            putMatrixComponents(values, "gbufferPreviousProjectionInverse", previous.projectionInverse(), 4);
            org.joml.Matrix4f modelViewProjection = new org.joml.Matrix4f().set(snapshot.projection())
                    .mul(new org.joml.Matrix4f().set(snapshot.modelView()));
            putMatrixComponents(values, "gl_ModelViewProjectionMatrix",
                    modelViewProjection.get(new float[16]), 4);
            putMatrix3Components(values, "gl_NormalMatrix", snapshot.normalMatrix());
            org.joml.Vector3f up = new org.joml.Matrix4f().set(snapshot.modelView())
                    .transformDirection(0.0f, 100.0f, 0.0f, new org.joml.Vector3f());
            putVector3(values, "upPosition", up.x, up.y, up.z);
            putSunPositionComponents(values, snapshot.modelView(), celestialAngle);
            double sx = values.get("sunPosition.x");
            double sy = values.get("sunPosition.y");
            double sz = values.get("sunPosition.z");
            putVector3(values, "moonPosition", -sx, -sy, -sz);
            boolean sunIsShadowSource = normalizedSunAngle <= 0.5f;
            putVector3(values, "shadowLightPosition",
                    sunIsShadowSource ? sx : -sx,
                    sunIsShadowSource ? sy : -sy,
                    sunIsShadowSource ? sz : -sz);
            putVector3(values, "eyePosition", eyePosition(player, snapshot));
            putVector3(values, "relativeEyePosition",
                    snapshot.cameraX() - values.get("eyePosition.x"),
                    snapshot.cameraY() - values.get("eyePosition.y"),
                    snapshot.cameraZ() - values.get("eyePosition.z"));
            putVector3(values, "playerLookVector", entityVector(player, "getViewVector", 0.0f, 0.0f, -1.0f));
            putVector3(values, "playerBodyVector", entityVector(player, "getForward", 0.0f, 0.0f, -1.0f));
            Object vehicle = invokeObject(player, "getVehicle");
            putVector3(values, "vehicleLookVector", entityVector(vehicle, "getForward", 0.0f, 0.0f, -1.0f));
            double[] vehiclePosition = entityPosition(vehicle);
            putVector3(values, "relativeVehiclePosition",
                    snapshot.cameraX() - vehiclePosition[0],
                    snapshot.cameraY() - vehiclePosition[1],
                    snapshot.cameraZ() - vehiclePosition[2]);
            putVector3(values, "skyColor", skyColor(level, snapshot));
            putVector3(values, "fogColor", skyColor(level, snapshot));
            putVector4(values, "lightningBoltPosition", 0.0, 0.0, 0.0, 0.0);
            putVector3(values, "endFlashPosition", 0.0, 0.0, 0.0);
            putVector3(values, "currentSelectedBlockPos", 0.0, 0.0, 0.0);
            putVector3(values, "chunkOffset", 0.0, 0.0, 0.0);
            putVector4(values, "entityColor", 0.0, 0.0, 0.0, 0.0);
            putVector4(values, "blendFunc", 1.0, 0.0, 1.0, 0.0);
            putMatrixComponents(values, "shadowModelView", NvidiumShadowRenderer.shadowModelView(), 4);
            putMatrixComponents(values, "shadowProjection", NvidiumShadowRenderer.shadowProjection(), 4);
            putMatrixComponents(values, "shadowModelViewInverse",
                    new org.joml.Matrix4f().set(NvidiumShadowRenderer.shadowModelView()).invert().get(new float[16]), 4);
            putMatrixComponents(values, "shadowProjectionInverse",
                    new org.joml.Matrix4f().set(NvidiumShadowRenderer.shadowProjection()).invert().get(new float[16]), 4);
        }
        return java.util.Map.copyOf(values);
    }

    static void putIrisBuiltInDefaults(java.util.Map<String, Double> values) {
        String[] scalars = {
                "eyeAltitude", "firstPersonCamera", "isEyeInWater", "isSpectator", "isRightHanded",
                "blindness", "darknessFactor", "darknessLightFactor", "nightVision", "playerMood",
                "constantMood", "currentPlayerAir", "maxPlayerAir", "currentPlayerArmor", "maxPlayerArmor",
                "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger", "maxPlayerHunger",
                "vehicleInWater", "isRiding", "inSwimmingAnimation", "feetInWater", "isElytraFlying",
                "is_burning", "is_hurt", "is_invisible", "is_on_ground", "is_sneaking", "is_sprinting",
                "hideGUI", "viewHeight", "viewWidth", "aspectRatio", "screenBrightness", "frameCounter",
                "frameTime", "frameTimeCounter", "currentColorSpace", "entityId", "blockEntityId",
                "vehicleId", "currentRenderedItemId", "currentSelectedBlockId", "heldItemId", "heldItemId2",
                "heldBlockLightValue", "heldBlockLightValue2", "sunAngle", "shadowAngle", "moonPhase",
                "rainStrength", "wetness", "thunderStrength", "worldTime", "worldDay", "endFlashIntensity",
                "previousEndFlashIntensity", "biome", "biome_category", "biome_precipitation", "rainfall",
                "temperature", "ambientLight", "bedrockLevel", "cloudHeight", "hasCeiling", "hasSkylight",
                "heightLimit", "logicalHeightLimit", "near", "far", "alphaTestRef", "renderStage",
                "fogDensity", "fogStart", "fogEnd", "fogMode", "fogShape", "textureFilteringMode",
                "centerDepthSmooth", "dhRenderDistance", "dhNearPlane", "dhFarPlane", "textureReloadCount",
                "gtextureId", "anisotropicFiltering", "pi"
        };
        for (String name : scalars) values.putIfAbsent(name, 0.0);
        values.put("maxPlayerHealth", 20.0);
        values.put("maxPlayerHunger", 20.0);
        values.put("maxPlayerArmor", 20.0);
        values.put("maxPlayerAir", 300.0);
        values.put("near", 0.05);
        values.put("far", 512.0);
        values.put("alphaTestRef", 0.1);
        values.put("pi", Math.PI);
        putVector2(values, "eyeBrightness", 0.0, 0.0);
        putVector2(values, "eyeBrightnessSmooth", 0.0, 0.0);
        putVector2(values, "atlasSize", 0.0, 0.0);
        putVector2(values, "gtextureSize", 0.0, 0.0);
        putVector2(values, "currentYearTime", 0.0, 0.0);
        putVector2(values, "viewSize", 1.0, 1.0);
        putVector2(values, "iris_ScreenSize", 1.0, 1.0);
        putVector3(values, "cameraPosition", 0.0, 0.0, 0.0);
        putVector3(values, "previousCameraPosition", 0.0, 0.0, 0.0);
        putVector3(values, "cameraPositionInt", 0.0, 0.0, 0.0);
        putVector3(values, "cameraPositionFract", 0.0, 0.0, 0.0);
        putVector3(values, "previousCameraPositionInt", 0.0, 0.0, 0.0);
        putVector3(values, "previousCameraPositionFract", 0.0, 0.0, 0.0);
        putVector3(values, "eyePosition", 0.0, 0.0, 0.0);
        putVector3(values, "relativeEyePosition", 0.0, 0.0, 0.0);
        putVector3(values, "playerBodyVector", 0.0, 0.0, -1.0);
        putVector3(values, "playerLookVector", 0.0, 0.0, -1.0);
        putVector3(values, "vehicleLookVector", 0.0, 0.0, -1.0);
        putVector3(values, "relativeVehiclePosition", 0.0, 0.0, 0.0);
        putVector3(values, "upPosition", 0.0, 100.0, 0.0);
        putVector3(values, "sunPosition", 0.0, 100.0, 0.0);
        putVector3(values, "moonPosition", 0.0, -100.0, 0.0);
        putVector3(values, "shadowLightPosition", 0.0, 100.0, 0.0);
        putVector3(values, "skyColor", 0.0, 0.0, 0.0);
        putVector3(values, "fogColor", 0.0, 0.0, 0.0);
        putVector3(values, "endFlashPosition", 0.0, 0.0, 0.0);
        putVector3(values, "currentSelectedBlockPos", 0.0, 0.0, 0.0);
        putVector3(values, "chunkOffset", 0.0, 0.0, 0.0);
        putVector4(values, "lightningBoltPosition", 0.0, 0.0, 0.0, 0.0);
        putVector4(values, "entityColor", 0.0, 0.0, 0.0, 0.0);
        putVector4(values, "blendFunc", 1.0, 0.0, 1.0, 0.0);
        String[] matrices = {
                "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse",
                "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse",
                "gbufferPreviousModelView", "gbufferPreviousProjection", "gl_ModelViewMatrix",
                "gl_ModelViewMatrixInverse", "gl_ProjectionMatrix", "gl_ProjectionMatrixInverse",
                "gl_ModelViewProjectionMatrix"
        };
        for (String name : matrices) {
            float[] identity = new org.joml.Matrix4f().get(new float[16]);
            putMatrixComponents(values, name, identity, 4);
        }
        putMatrix3Components(values, "gl_NormalMatrix", new org.joml.Matrix3f().get(new float[9]));
        values.put("CAT_NONE", 0.0);
        String[] categories = {"TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA",
                "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP",
                "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND"};
        for (int i = 0; i < categories.length; i++) values.put("CAT_" + categories[i], (double) (i + 1));
        values.put("PPT_NONE", 0.0);
        values.put("PPT_RAIN", 1.0);
        values.put("PPT_SNOW", 2.0);
        values.put("BIOME_PALE_GARDEN", -1.0);
        values.put("IS_IRIS", 1.0);
        values.put("MC_VERSION", 12111.0);
    }

    private static void putDimensionUniforms(java.util.Map<String, Double> values, Object level) {
        Object dimension = invokeObject(level, "dimensionType");
        if (dimension != null && invokeObject(dimension, "value") != null) dimension = invokeObject(dimension, "value");
        putScalar(values, "ambientLight", invokeDouble(dimension, "ambientLight", 0.0));
        putScalar(values, "hasCeiling", invokeBoolean(dimension, "hasCeiling", false) ? 1.0 : 0.0);
        putScalar(values, "hasSkylight", invokeBoolean(dimension, "hasSkyLight", false) ? 1.0 : 0.0);
        putScalar(values, "bedrockLevel", invokeDouble(level, "getMinY", 0.0));
        double minY = invokeDouble(level, "getMinY", 0.0);
        double maxY = invokeDouble(level, "getMaxY", minY + 384.0);
        putScalar(values, "heightLimit", maxY - minY);
        putScalar(values, "logicalHeightLimit", invokeDouble(dimension, "logicalHeight", maxY - minY));
        Object effects = invokeObject(level, "effects");
        putScalar(values, "cloudHeight", invokeDouble(effects, "cloudHeight", 192.0));
    }

    private static void putBiomeIdentity(java.util.Map<String, Double> values, Object biome) {
        if (biome == null) return;
        try {
            int id = BuiltInRegistries.BIOME.getId((net.minecraft.world.level.biome.Biome) biome);
            putScalar(values, "biome", id);
            var key = BuiltInRegistries.BIOME.getKey((net.minecraft.world.level.biome.Biome) biome);
            String path = key == null ? "" : key.getPath().toLowerCase(java.util.Locale.ROOT);
            int category = biomeCategory(path);
            putScalar(values, "biome_category", category);
            for (net.minecraft.world.level.biome.Biome registered : BuiltInRegistries.BIOME) {
                var registeredKey = BuiltInRegistries.BIOME.getKey(registered);
                if (registeredKey != null) {
                    values.put("BIOME_" + registeredKey.getPath().toUpperCase(java.util.Locale.ROOT),
                            (double) BuiltInRegistries.BIOME.getId(registered));
                }
            }
            if (path.equals("pale_garden")) values.put("BIOME_PALE_GARDEN", (double) id);
            Object precipitation = precipitationAt(Minecraft.getInstance().level,
                    Minecraft.getInstance().player);
            putScalar(values, "biome_precipitation", precipitationCode(precipitation));
        } catch (RuntimeException ignored) {
            // Keep the complete, typed default set when registry state is unavailable.
        }
    }

    private static int biomeCategory(String path) {
        if (path.contains("nether")) return 16;
        if (path.contains("end")) return 8;
        if (path.contains("mushroom")) return 15;
        if (path.contains("swamp")) return 14;
        if (path.contains("river")) return 13;
        if (path.contains("desert")) return 12;
        if (path.contains("ocean")) return 11;
        if (path.contains("beach") || path.contains("shore")) return 9;
        if (path.contains("forest") || path.contains("grove")) return 10;
        if (path.contains("snow") || path.contains("ice") || path.contains("frozen")) return 7;
        if (path.contains("savanna")) return 6;
        if (path.contains("plains")) return 5;
        if (path.contains("badlands") || path.contains("mesa")) return 4;
        if (path.contains("jungle") || path.contains("bamboo")) return 3;
        if (path.contains("mountain") || path.contains("peak") || path.contains("slope")) return 2;
        if (path.contains("taiga")) return 1;
        return 0;
    }

    private static Object currentBiome(Object level, Object player) {
        if (level == null || player == null) return null;
        try {
            Object position = invokeObject(player, "blockPosition");
            Object holder = invokeCompatible(level, "getBiome", position);
            return invokeObject(holder, "value");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object precipitationAt(Object level, Object player) {
        if (level == null || player == null) return null;
        try {
            Object position = invokeObject(player, "blockPosition");
            return invokeCompatible(level, "getPrecipitationAt", position);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static int precipitationCode(Object precipitation) {
        if (!(precipitation instanceof Enum<?> value)) return 0;
        return switch (value.name()) {
            case "RAIN" -> 1;
            case "SNOW" -> 2;
            default -> 0;
        };
    }

    private static int eyeBrightness(Object level, Object player, boolean sky) {
        if (level == null || player == null) return 0;
        try {
            Object position = invokeObject(player, "blockPosition");
            Class<?> lightLayer = Class.forName("net.minecraft.world.level.LightLayer");
            Object layer = lightLayer.getField(sky ? "SKY" : "BLOCK").get(null);
            Object brightness = invokeCompatible(level, "getBrightness", layer, position);
            return brightness instanceof Number number ? number.intValue() * 16 : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean effectPresent(Object player, String effectPath) {
        if (player == null) return false;
        Object effects = invokeObject(player, "getActiveEffects");
        if (!(effects instanceof Iterable<?> iterable)) return false;
        for (Object instance : iterable) {
            Object holder = invokeObject(instance, "getEffect");
            Object effect = invokeObject(holder, "value");
            if (effect == null) effect = holder;
            try {
                Object key = BuiltInRegistries.MOB_EFFECT.getKey((net.minecraft.world.effect.MobEffect) effect);
                if (key != null && key.getPath().equals(effectPath)) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private static boolean vehicleInWater(Object player) {
        Object vehicle = invokeObject(player, "getVehicle");
        return invokeBoolean(vehicle, "isInWater", false);
    }

    private static boolean isRightHanded(Minecraft client) {
        Object mainHand = invokeObject(client.options, "mainHand");
        Object value = invokeObject(mainHand, "get");
        return value instanceof Enum<?> arm && arm.name().equals("RIGHT");
    }

    private static boolean isFirstPerson(Minecraft client) {
        Object cameraType = invokeObject(client.options, "getCameraType");
        return cameraType == null || !(cameraType instanceof Enum<?> type)
                || type.name().equals("FIRST_PERSON");
    }

    private static boolean optionBoolean(Minecraft client, String option, boolean fallback) {
        Object value = invokeObject(client.options, option);
        Object selected = invokeObject(value, "get");
        return selected instanceof Boolean result ? result : fallback;
    }

    private static double optionDouble(Minecraft client, String option, double fallback) {
        Object value = invokeObject(client.options, option);
        Object selected = invokeObject(value, "get");
        return selected instanceof Number number ? number.doubleValue() : fallback;
    }

    private static Object invokeObject(Object target, String name) {
        if (target == null) return null;
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static Object invokeCompatible(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean compatible = true;
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] != null && !isReflectionArgumentCompatible(
                        method.getParameterTypes()[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(name);
    }

    private static boolean isReflectionArgumentCompatible(Class<?> parameter, Object argument) {
        if (parameter.isInstance(argument)) return true;
        if (!parameter.isPrimitive()) return false;
        return (parameter == float.class && argument instanceof Float)
                || (parameter == double.class && argument instanceof Double)
                || (parameter == int.class && argument instanceof Integer)
                || (parameter == long.class && argument instanceof Long)
                || (parameter == boolean.class && argument instanceof Boolean)
                || (parameter == short.class && argument instanceof Short)
                || (parameter == byte.class && argument instanceof Byte)
                || (parameter == char.class && argument instanceof Character);
    }

    private static double[] eyePosition(Object player, Snapshot snapshot) {
        if (player != null) {
            for (Method method : player.getClass().getMethods()) {
                if (method.getName().equals("getEyePosition") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == float.class) {
                    try { return vector3Array(method.invoke(player, 1.0f)); }
                    catch (ReflectiveOperationException | RuntimeException ignored) { break; }
                }
            }
        }
        return new double[]{snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ()};
    }

    private static double[] entityVector(Object entity, String method, double x, double y, double z) {
        if (entity != null) {
            for (Method candidate : entity.getClass().getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterCount() <= 1) {
                    try {
                        Object value = candidate.getParameterCount() == 0
                                ? candidate.invoke(entity) : candidate.invoke(entity, 1.0f);
                        return vector3Array(value);
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            }
        }
        return new double[]{x, y, z};
    }

    private static double[] entityPosition(Object entity) {
        if (entity == null) return new double[]{0.0, 0.0, 0.0};
        return new double[]{invokeDouble(entity, "getX", 0.0),
                invokeDouble(entity, "getY", 0.0), invokeDouble(entity, "getZ", 0.0)};
    }

    private static double[] skyColor(Object level, Snapshot snapshot) {
        if (level != null && snapshot != null) {
            try {
                Class<?> vec3Type = Class.forName("net.minecraft.world.phys.Vec3");
                Object position = vec3Type.getConstructor(double.class, double.class, double.class)
                        .newInstance((double) snapshot.cameraX(), (double) snapshot.cameraY(), (double) snapshot.cameraZ());
                Object color = invokeCompatible(level, "getSkyColor", position, 1.0f);
                return vector3Array(color);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return new double[]{0.0, 0.0, 0.0};
    }

    private static double[] vector3Array(Object vector) {
        if (vector == null) return new double[]{0.0, 0.0, 0.0};
        return new double[]{component(vector, "x"), component(vector, "y"), component(vector, "z")};
    }

    private static double component(Object object, String name) {
        if (object == null) return 0.0;
        try {
            Object value = object.getClass().getMethod(name).invoke(object);
            if (value instanceof Number number) return number.doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        try {
            Field field = object.getClass().getField(name);
            Object value = field.get(object);
            if (value instanceof Number number) return number.doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return 0.0;
    }

    private static double fract(double value) { return value - Math.floor(value); }

    private static void putScalar(java.util.Map<String, Double> values, String name, double value) {
        values.put(name, Double.isFinite(value) ? value : 0.0);
    }

    private static void putVector2(java.util.Map<String, Double> values, String name, double x, double y) {
        values.put(name + ".x", x); values.put(name + ".y", y);
        values.put(name + ".r", x); values.put(name + ".g", y);
    }

    private static void putVector3(java.util.Map<String, Double> values, String name, double x, double y, double z) {
        values.put(name + ".x", x); values.put(name + ".y", y); values.put(name + ".z", z);
        values.put(name + ".r", x); values.put(name + ".g", y); values.put(name + ".b", z);
    }

    private static void putVector4(java.util.Map<String, Double> values, String name,
                                   double x, double y, double z, double w) {
        putVector3(values, name, x, y, z);
        values.put(name + ".w", w); values.put(name + ".a", w);
    }

    private static void putMatrix3Components(java.util.Map<String, Double> values, String name, float[] matrix) {
        putMatrixComponents(values, name, matrix, 3);
    }

    static void putBiomeUniforms(java.util.Map<String, Double> values,
                                 float temperature, float rainfall) {
        values.put("temperature", (double) (Float.isFinite(temperature) ? temperature : 0.0f));
        values.put("rainfall", (double) (Float.isFinite(rainfall) ? rainfall : 0.0f));
    }

    static void putSunPositionComponents(java.util.Map<String, Double> values,
                                         float[] modelView, float sunAngle) {
        // Minecraft's SkyRenderer builds the celestial pose as Y(-90 degrees) * X(sunAngle),
        // then multiplies it by the active camera model-view matrix before translating the
        // sun quad to (0, 100, 0). Iris exposes that transformed point in view space.
        org.joml.Matrix4f celestial = new org.joml.Matrix4f()
                .rotationY((float) -Math.PI / 2.0f)
                .rotateX(sunAngle);
        org.joml.Vector3f sunPosition = new org.joml.Matrix4f()
                .set(modelView)
                .mul(celestial)
                .transformPosition(0.0f, 100.0f, 0.0f, new org.joml.Vector3f());
        values.put("sunPosition.x", (double) sunPosition.x);
        values.put("sunPosition.y", (double) sunPosition.y);
        values.put("sunPosition.z", (double) sunPosition.z);
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
