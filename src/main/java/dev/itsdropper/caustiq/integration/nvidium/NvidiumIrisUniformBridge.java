package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL32C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BiomeTags;
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
    private static volatile long lastCaptureNanos;
    private static volatile boolean warnedUploadFailure;
    private static volatile float taaOffsetX;
    private static volatile float taaOffsetY;
    private static volatile float centerDepthSmooth = 1.0f;
    private static volatile boolean centerDepthCaptureDisabled;
    private static volatile boolean warnedCenterDepthFailure;
    private static final int[] CENTER_DEPTH_PBOS = new int[2];
    private static final long[] CENTER_DEPTH_FENCES = new long[2];
    private static final long[] CENTER_DEPTH_SEQUENCES = new long[2];
    private static long centerDepthSequence;
    private static int centerDepthWriteIndex;
    private static int wetnessFrame = Integer.MIN_VALUE;
    private static float wetnessForCurrentFrame;

    private NvidiumIrisUniformBridge() { }

    /** Called at NvidiumWorldRenderer.renderFrame HEAD, before its GPU draw phases. */
    public static void capture(Object chunkRenderMatrices, double cameraX, double cameraY, double cameraZ) {
        if (chunkRenderMatrices == null) return;
        try {
            Matrix4fc projection = (Matrix4fc) invoke(chunkRenderMatrices, "projection");
            Matrix4fc modelView = (Matrix4fc) invoke(chunkRenderMatrices, "modelView");
            int frameCounter = FRAME_COUNTER.incrementAndGet();
            if (isTaaEnabled()) {
                var target = Minecraft.getInstance().getMainRenderTarget();
                NvidiumTaaJitter.Offset offset = NvidiumTaaJitter.forFrame(
                        frameCounter, target.width, target.height);
                taaOffsetX = offset.x();
                taaOffsetY = offset.y();
            } else {
                taaOffsetX = 0.0f;
                taaOffsetY = 0.0f;
            }
            Matrix4f projectionInverse = new Matrix4f(projection).invert();
            Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
            Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();
            long now = System.nanoTime();
            if (worldStartNanos == 0L) worldStartNanos = now;
            float frameTime = lastCaptureNanos == 0L ? 1.0f / 60.0f
                    : Math.max(0.0f, (now - lastCaptureNanos) / 1_000_000_000.0f);
            lastCaptureNanos = now;

            Snapshot next = new Snapshot(
                    matrix(projection),
                    matrix(modelView),
                    matrix(projectionInverse),
                    matrix(modelViewInverse),
                    matrix3(normalMatrix),
                    (float) cameraX,
                    (float) cameraY,
                    (float) cameraZ,
                    frameTime,
                    (now - worldStartNanos) / 1_000_000_000.0f,
                    frameCounter);
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
            int frameTimeLocation = GL20C.glGetUniformLocation(program, "caustiqFrameTime");
            if (frameTimeLocation >= 0) GL20C.glUniform1f(frameTimeLocation, snapshot.frameTime());
            int timeLocation = GL20C.glGetUniformLocation(program, "caustiqFrameTimeCounter");
            if (timeLocation >= 0) GL20C.glUniform1f(timeLocation, snapshot.frameTimeCounter());
            int frameLocation = GL20C.glGetUniformLocation(program, "caustiqFrameCounter");
            if (frameLocation >= 0) GL20C.glUniform1i(frameLocation, snapshot.frameCounter());
            uploadPhotonUniforms(program, snapshot);
            int firstCustomTextureUnit;
            if (NvidiumShadowRenderer.isShadowRasterization()) {
                firstCustomTextureUnit = NvidiumIrisTextureBridge.bindForShadow(program);
            } else {
                firstCustomTextureUnit = NvidiumIrisTextureBridge.bindForTerrain(program);
            }
            ShaderRuntime.uploadTerrainCustomUniforms(program);
            ShaderRuntime.bindTerrainCustomTextures(program, firstCustomTextureUnit);
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
        float rain = clamp01(invokeFloat(level, "getRainLevel", 1.0f, 0.0f));
        float wetness = wetnessForFrame(rain, snapshot.frameCounter());
        float worldAge = invokeLong(level, "getGameTime", 0L);
        int worldTime = (int) Math.floorMod(invokeLong(level, "getDayTime", 0L), 24_000L);
        float dayProgress = worldTime / 24_000.0f;
        float sunAngle = invokeFloat(level, "getSunAngle", 1.0f, 0.0f);
        Minecraft client = Minecraft.getInstance();
        double sunDegrees = attributeNumber(client, "SUN_ANGLE", Math.toDegrees(sunAngle));
        double moonDegrees = attributeNumber(client, "MOON_ANGLE", sunDegrees + 180.0);
        boolean isDay = normalizeDegrees(sunDegrees + 90.0) < 180.0;
        float sunPathRotation = parseFloat(ShaderRuntime.selectedDefine("sunPathRotation", "0.0"), 0.0f);
        org.joml.Vector3f sunDirection = celestialDirection(
                snapshot.modelView(), sunDegrees, sunPathRotation);
        org.joml.Vector3f lightDirection = celestialDirection(
                snapshot.modelView(), isDay ? sunDegrees : moonDegrees, sunPathRotation);
        int[] viewport = currentViewportSize();
        float aspectRatio = viewport[1] > 0 ? (float) viewport[0] / (float) viewport[1] : 1.0f;

        scalar(program, "caustiqNear", near);
        scalar(program, "caustiqFar", far);
        scalar(program, "caustiqAspectRatio", aspectRatio);
        scalar(program, "caustiqSunAngle", (float) (normalizeDegrees(sunDegrees + 90.0) / 360.0));
        Object cameraEntity = invokeObject(client, "getCameraEntity");
        scalar(program, "caustiqBlindness", (float) blindnessFactor(cameraEntity));
        scalar(program, "caustiqNightVision", (float) nightVisionFactor(client, cameraEntity));
        scalar(program, "caustiqDarknessFactor", (float) darknessFactor(cameraEntity));
        scalar(program, "caustiqEyeAltitude", (float) invokeDouble(cameraEntity, "getEyeY", snapshot.cameraY()));
        scalar(program, "caustiqEyeSkylight", eyeBrightness(level, cameraEntity, true) / 240.0f);
        Object player = client.player;
        vector3(program, "caustiqRelativeEyePosition",
                (float) (invokeDouble(player, "getX", snapshot.cameraX()) - snapshot.cameraX()),
                (float) (invokeDouble(player, "getEyeY", snapshot.cameraY()) - snapshot.cameraY()),
                (float) (invokeDouble(player, "getZ", snapshot.cameraZ()) - snapshot.cameraZ()));
        int[] atlasSize = blockAtlasSize();
        integer2(program, "caustiqAtlasSize", atlasSize[0], atlasSize[1]);
        scalar(program, "caustiqRainStrength", rain);
        scalar(program, "caustiqWetness", wetness);
        scalar(program, "caustiqFrameTime", snapshot.frameTime());
        vector2(program, "caustiqTaaOffset", taaOffsetX, taaOffsetY);
        vector3(program, "caustiqLightDirection",
                lightDirection.x, lightDirection.y, lightDirection.z);
        vector3(program, "caustiqSunDirection",
                sunDirection.x, sunDirection.y, sunDirection.z);
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

    private static double[] heldBlockLightColor(InteractionHand hand) {
        Object player = Minecraft.getInstance().player;
        if (player == null) return new double[]{1.0, 1.0, 1.0};
        try {
            Object stack = invokeCompatible(player, "getItemInHand", hand);
            Object item = invokeObject(stack, "getItem");
            if (item != null) {
                for (Method method : item.getClass().getMethods()) {
                    if (method.getName().equals("getLightColor") && method.getParameterCount() == 2) {
                        Object color = method.invoke(item, player, stack);
                        if (color != null) return vector3Array(color);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return new double[]{1.0, 1.0, 1.0};
    }

    private static void selectedBlockUniforms(java.util.Map<String, Double> values,
                                              Minecraft client, Object level, Snapshot snapshot) {
        putScalar(values, "currentSelectedBlockId", 0.0);
        putVector3(values, "currentSelectedBlockPos", -256.0, -256.0, -256.0);
        if (level == null) return;
        Object hitResult = readField(client, "hitResult");
        Object hitType = invokeObject(hitResult, "getType");
        if (hitType == null || !hitType.toString().equals("BLOCK")) return;
        try {
            Object position = invokeObject(hitResult, "getBlockPos");
            Object state = invokeCompatible(level, "getBlockState", position);
            if (state instanceof net.minecraft.world.level.block.state.BlockState blockState) {
                putScalar(values, "currentSelectedBlockId",
                        NvidiumIrisVertexContext.blockIdForState(blockState));
            }
            Object center = invokeObject(position, "getCenter");
            double[] centerPosition = vector3Array(center);
            double cameraX = snapshot == null ? 0.0 : snapshot.cameraX();
            double cameraY = snapshot == null ? 0.0 : snapshot.cameraY();
            double cameraZ = snapshot == null ? 0.0 : snapshot.cameraZ();
            putVector3(values, "currentSelectedBlockPos", centerPosition[0] - cameraX,
                    centerPosition[1] - cameraY, centerPosition[2] - cameraZ);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
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
    private static double sodiumFogParameter(Minecraft client, String accessor, double fallback) {
        Object parameters = invokeObject(client.gameRenderer, "sodium$getFogParameters");
        return invokeDouble(parameters, accessor, fallback);
    }

    private static int textureFilteringMode(Minecraft client) {
        Object option = invokeObject(client.options, "textureFiltering");
        Object value = invokeObject(option, "get");
        if (!(value instanceof Enum<?> mode)) return 0;
        return switch (mode.name()) {
            case "RGSS" -> 1;
            case "ANISOTROPIC" -> 2;
            default -> 0;
        };
    }

    private static int irisColorSpace() {
        try {
            Class<?> settings = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings");
            Object colorSpace = settings.getField("colorSpace").get(null);
            return colorSpace instanceof Enum<?> value ? value.ordinal() : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static double[] fogColor(Snapshot snapshot) {
        try {
            Class<?> stateType = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = stateType.getField("INSTANCE").get(null);
            Object color = invokeObject(state, "getFogColor");
            if (color != null) return vector3Array(color);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return skyColor(Minecraft.getInstance().level, snapshot);
    }

    private static double[] lightningBoltPosition(Object level, Snapshot snapshot) {
        if (level == null || snapshot == null) return new double[]{0.0, 0.0, 0.0, 0.0};
        Object entities = invokeObject(level, "entitiesForRendering");
        if (!(entities instanceof Iterable<?> iterable)) return new double[]{0.0, 0.0, 0.0, 0.0};
        for (Object entity : iterable) {
            if (!entity.getClass().getSimpleName().contains("LightningBolt")) continue;
            double[] position = entityPosition(entity);
            return new double[]{position[0] - snapshot.cameraX(),
                    position[1] - snapshot.cameraY(), position[2] - snapshot.cameraZ(), 1.0};
        }
        return new double[]{0.0, 0.0, 0.0, 0.0};
    }

    private static double[] endFlashPosition(Object level, Snapshot snapshot) {
        if (level == null || snapshot == null) return new double[]{0.0, 0.0, 0.0};
        Object state = invokeObject(level, "endFlashState");
        if (state == null) return new double[]{0.0, 0.0, 0.0};
        double yaw = invokeDouble(state, "getYAngle", 0.0);
        double pitch = invokeDouble(state, "getXAngle", 0.0);
        org.joml.Matrix4f matrix = new org.joml.Matrix4f().set(snapshot.modelView())
                .rotateY((float) Math.toRadians(180.0 - yaw))
                .rotateX((float) Math.toRadians(-90.0 - pitch));
        org.joml.Vector3f position = matrix.transformPosition(0.0f, 100.0f, 0.0f, new org.joml.Vector3f());
        return new double[]{position.x, position.y, position.z};
    }

    private static double attributeNumber(Minecraft client, String name, double fallback) {
        Object value = environmentAttribute(client, name);
        if (value instanceof Number number) return number.doubleValue();
        Object index = invokeObject(value, "index");
        return index instanceof Number number ? number.doubleValue() : fallback;
    }

    private static Object environmentAttribute(Minecraft client, String name) {
        try {
            Object camera = invokeObject(client.gameRenderer, "getMainCamera");
            Object probe = invokeObject(camera, "attributeProbe");
            Class<?> attributes = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
            Object key = attributes.getField(name).get(null);
            Object tracker = invokeObject(client, "getDeltaTracker");
            Object partial = invokeCompatibleQuietly(tracker, "getGameTimeDeltaPartialTick", false);
            if (!(partial instanceof Number)) partial = 1.0f;
            return invokeCompatibleQuietly(probe, "getValue", key, partial);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static long worldTimeForUniform(Object level, long dayTime) {
        Object dimensionType = invokeObject(level, "dimensionType");
        Object dimension = invokeObject(level, "dimension");
        Object identifier = invokeObject(dimension, "identifier");
        String path = String.valueOf(invokeObject(identifier, "getPath"));
        boolean fixedTime = invokeBoolean(dimensionType, "hasFixedTime", false);
        if (fixedTime && !path.equals("the_nether") && !path.equals("the_end")) return 0L;
        return Math.floorMod(dayTime, 24_000L);
    }

    private static int moonPhase(Object level) {
        return moonPhase(Minecraft.getInstance(), level);
    }

    private static int moonPhase(Minecraft client, Object level) {
        Object value = environmentAttribute(client, "MOON_PHASE");
        Object index = invokeObject(value, "index");
        if (index instanceof Number number) return number.intValue();
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
            Object position = invokeObject(player, "blockPosition");
            Object holder = invokeCompatible(level, "getBiome", position);
            Object biome = invokeObject(holder, "value");
            Object value = invokeObject(biome, methodName);
            if (value instanceof Number number) return number.floatValue();

            // Vanilla keeps downfall in Biome.ClimateSettings; Iris exposes it through an
            // ExtendedBiome mixin. Read the record directly when Iris is not installed.
            Object climate = readField(biome, "climateSettings");
            if (climate == null) climate = readField(biome, "modifiedClimateSettings");
            String component = methodName.equals("getDownfall") ? "downfall" : "temperature";
            value = invokeObject(climate, component);
            if (!(value instanceof Number)) value = readField(climate, component);
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
        return expressionVariables(viewWidth, viewHeight, java.util.Map.of());
    }

    public static java.util.Map<String, Double> expressionVariables(
            int viewWidth, int viewHeight, java.util.Map<String, String> selectedDefines) {
        java.util.Map<String, Double> values = new java.util.HashMap<>();
        putIrisBuiltInDefaults(values);

        Minecraft client = Minecraft.getInstance();
        Object player = client.player;
        Object cameraEntity = invokeObject(client, "getCameraEntity");
        Object level = client.level;
        Snapshot snapshot = CURRENT.get();
        Snapshot previous = PREVIOUS.get() == null ? snapshot : PREVIOUS.get();
        long worldAge = invokeLong(level, "getGameTime", 0L);
        long dayTime = invokeLong(level, "getDayTime", 0L);
        long timeOfDay = worldTimeForUniform(level, dayTime);
        double fallbackSunDegrees = Math.toDegrees(invokeFloat(level, "getSunAngle", 1.0f, 0.0f));
        double sunDegrees = attributeNumber(client, "SUN_ANGLE", fallbackSunDegrees);
        double moonDegrees = attributeNumber(client, "MOON_ANGLE", sunDegrees + 180.0);
        double sunAngleDegrees = normalizeDegrees(sunDegrees + 90.0);
        double moonAngleDegrees = normalizeDegrees(moonDegrees + 90.0);
        float normalizedSunAngle = (float) (sunAngleDegrees / 360.0);
        boolean isDay = sunAngleDegrees < 180.0;
        float sunPathRotation = parseFloat(selectedDefines == null ? null : selectedDefines.get("sunPathRotation"), 0.0f);
        float celestialAngle = (float) Math.toRadians(sunDegrees);
        float rain = clamp01(invokeFloat(level, "getRainLevel", 1.0f, 0.0f));
        float thunder = clamp01(invokeFloat(level, "getThunderLevel", 1.0f, 0.0f));
        float near = 0.05f;
        double far = Math.max(16.0, invokeDouble(client.options, "getEffectiveRenderDistance", 32.0) * 16.0);

        putScalar(values, "frameCounter", snapshot == null ? FRAME_COUNTER.get() : snapshot.frameCounter());
        putScalar(values, "frameTime", snapshot == null ? 1.0 / 60.0 : snapshot.frameTime());
        putScalar(values, "frameTimeCounter", snapshot == null ? 0.0 : snapshot.frameTimeCounter());
        putScalar(values, "worldAge", worldAge);
        putScalar(values, "worldDay", Math.floorDiv(dayTime, 24_000L));
        putScalar(values, "worldTime", timeOfDay);
        putScalar(values, "sunAngle", normalizedSunAngle);
        putScalar(values, "shadowAngle", isDay ? normalizedSunAngle : moonAngleDegrees / 360.0);
        putScalar(values, "rainStrength", rain);
        putScalar(values, "wetness", wetnessForFrame(rain,
                snapshot == null ? FRAME_COUNTER.get() : snapshot.frameCounter()));
        putScalar(values, "thunderStrength", thunder);
        putScalar(values, "moonPhase", moonPhase(client, level));
        putScalar(values, "near", near);
        putScalar(values, "far", far);
        putScalar(values, "dhNearPlane", near);
        putScalar(values, "dhFarPlane", far);
        putScalar(values, "dhRenderDistance", far);
        putScalar(values, "viewWidth", viewWidth);
        putScalar(values, "viewHeight", viewHeight);
        putScalar(values, "aspectRatio", viewHeight <= 0 ? 1.0 : (double) viewWidth / viewHeight);
        putVector2(values, "iris_ScreenSize", viewWidth, viewHeight);
        putVector2(values, "viewSize", viewWidth, viewHeight);
        putVector2(values, "iris_ScreenSize", viewWidth, viewHeight);
        putVector2(values, "iris_ScreenSize", viewWidth, viewHeight);
        putScalar(values, "eyeAltitude", invokeDouble(cameraEntity, "getEyeY",
                snapshot == null ? 0.0 : snapshot.cameraY()));
        putScalar(values, "screenBrightness", optionDouble(client, "gamma", 0.5));
        putScalar(values, "isNight", isDay ? 0.0 : 1.0);
        putScalar(values, "isDay", isDay ? 1.0 : 0.0);
        putScalar(values, "isRaining", invokeBoolean(level, "isRaining", false) ? 1.0 : 0.0);
        putScalar(values, "isThundering", invokeBoolean(level, "isThundering", false) ? 1.0 : 0.0);
        putScalar(values, "isEyeInWater", isEyeInWater());
        putScalar(values, "isSpectator", invokeBoolean(player, "isSpectator", false) ? 1.0 : 0.0);
        putScalar(values, "isRightHanded", isRightHanded(client) ? 1.0 : 0.0);
        putScalar(values, "firstPersonCamera", isFirstPerson(client) ? 1.0 : 0.0);
        putScalar(values, "hideGUI", optionBoolean(client, "hideGui", false) ? 1.0 : 0.0);
        putScalar(values, "is_sneaking", invokeBoolean(player, "isCrouching", false) ? 1.0 : 0.0);
        putScalar(values, "is_sprinting", invokeBoolean(player, "isSprinting", false) ? 1.0 : 0.0);
        putScalar(values, "is_on_ground", invokeBoolean(player, "onGround", false) ? 1.0 : 0.0);
        putScalar(values, "is_burning", invokeBoolean(player, "isOnFire", false) ? 1.0 : 0.0);
        putScalar(values, "is_hurt", invokeIntField(player, "hurtTime", 0) > 0 ? 1.0 : 0.0);
        putScalar(values, "is_invisible", invokeBoolean(player, "isInvisible", false) ? 1.0 : 0.0);
        putScalar(values, "isElytraFlying", isElytraFlying() ? 1.0 : 0.0);
        putScalar(values, "inSwimmingAnimation", invokeBoolean(player, "isSwimming", false) ? 1.0 : 0.0);
        putScalar(values, "isRiding", invokeBoolean(player, "isPassenger", false) ? 1.0 : 0.0);
        putScalar(values, "feetInWater", invokeBoolean(player, "isInShallowWater", false) ? 1.0 : 0.0);
        putScalar(values, "vehicleInWater", vehicleInWater(player) ? 1.0 : 0.0);

        boolean survival = isSurvivalPlayer(client);
        double health = invokeDouble(player, "getHealth", 20.0);
        double maxHealth = invokeDouble(player, "getMaxHealth", 20.0);
        double armor = invokeDouble(player, "getArmorValue", 0.0);
        double hunger = invokeDouble(invokeObject(player, "getFoodData"), "getFoodLevel", 20.0);
        double air = invokeDouble(player, "getAirSupply", 300.0);
        double maxAir = invokeDouble(player, "getMaxAirSupply", 300.0);
        putScalar(values, "currentPlayerHealth", survival && maxHealth > 0 ? health / maxHealth : -1.0);
        putScalar(values, "maxPlayerHealth", survival ? maxHealth : -1.0);
        putScalar(values, "currentPlayerArmor", survival ? armor / 50.0 : -1.0);
        putScalar(values, "maxPlayerArmor", 50.0);
        putScalar(values, "currentPlayerHunger", survival ? hunger / 20.0 : -1.0);
        putScalar(values, "maxPlayerHunger", 20.0);
        putScalar(values, "currentPlayerAir", survival && maxAir > 0 ? air / maxAir : -1.0);
        putScalar(values, "maxPlayerAir", survival ? maxAir : -1.0);
        putScalar(values, "health", health);
        putScalar(values, "maxHealth", maxHealth);
        putScalar(values, "isInWater", invokeBoolean(player, "isInWater", false) ? 1.0 : 0.0);
        putScalar(values, "isInLava", invokeBoolean(player, "isInLava", false) ? 1.0 : 0.0);

        Object biome = currentBiome(level, player);
        putBiomeUniforms(values, biomeFloat(level, "getBaseTemperature"),
                biomeFloat(level, "getDownfall"));
        putBiomeIdentity(values, biome);
        putDimensionUniforms(values, level, client);
        putVector2(values, "eyeBrightness", eyeBrightness(level, cameraEntity, false),
                eyeBrightness(level, cameraEntity, true));
        putVector2(values, "eyeBrightnessSmooth",
                smoothUniform("eyeBrightnessSmooth.x", eyeBrightness(level, cameraEntity, false), 10.0, 10.0),
                smoothUniform("eyeBrightnessSmooth.y", eyeBrightness(level, cameraEntity, true), 10.0, 10.0));
        putScalar(values, "blindness", blindnessFactor(cameraEntity));
        putScalar(values, "darknessFactor", darknessFactor(cameraEntity));
        putScalar(values, "darknessLightFactor", irisCapturedFloat("getDarknessLightFactor", 0.0));
        putScalar(values, "nightVision", nightVisionFactor(client, cameraEntity));
        putScalar(values, "playerMood", invokeDouble(cameraEntity, "getCurrentMood", 0.0));
        putScalar(values, "constantMood", invokeDouble(cameraEntity, "getCurrentConstantMood", 0.0));

        int[] atlas = blockAtlasSize();
        putVector2(values, "atlasSize", atlas[0], atlas[1]);
        putVector2(values, "gtextureSize", atlas[0], atlas[1]);
        putScalar(values, "heldBlockLightValue", heldBlockLightValue(InteractionHand.MAIN_HAND));
        putScalar(values, "heldBlockLightValue2", heldBlockLightValue(InteractionHand.OFF_HAND));
        putVector3(values, "heldBlockLightColor", heldBlockLightColor(InteractionHand.MAIN_HAND));
        putVector3(values, "heldBlockLightColor2", heldBlockLightColor(InteractionHand.OFF_HAND));
        putScalar(values, "heldItemId", heldItemId(InteractionHand.MAIN_HAND));
        putScalar(values, "heldItemId2", heldItemId(InteractionHand.OFF_HAND));
        putScalar(values, "entityId", -1);
        putScalar(values, "blockEntityId", -1);
        putScalar(values, "currentRenderedItemId", -1);
        Object vehicle = invokeObject(player, "getVehicle");
        Object vehicleType = invokeObject(vehicle, "getType");
        putScalar(values, "vehicleId", vehicleType == null ? 0 : BuiltInRegistries.ENTITY_TYPE.getId(
                (net.minecraft.world.entity.EntityType<?>) vehicleType));
        selectedBlockUniforms(values, client, level, snapshot);
        putScalar(values, "textureReloadCount", irisCapturedFloat("getTextureReloadCount", 0.0));
        putScalar(values, "textureFilteringMode", 0);
        putScalar(values, "renderStage", 0);
        putScalar(values, "alphaTestRef", 0.1);
        putScalar(values, "fogMode", 0);
        putScalar(values, "fogShape", -1);
        double fogDensity = irisCapturedFloat("getFogDensity", 0.0);
        double fogStart = sodiumFogParameter(client, "environmentalStart", 0.0);
        double fogEnd = sodiumFogParameter(client, "environmentalEnd", far);
        putScalar(values, "fogDensity", Math.max(0.0, fogDensity));
        putScalar(values, "fogStart", fogStart);
        putScalar(values, "fogEnd", fogEnd);
        putScalar(values, "iris_FogStart", fogStart);
        putScalar(values, "iris_FogEnd", fogEnd);
        putScalar(values, "iris_FogDensity", Math.max(0.0, fogDensity));
        putScalar(values, "fogMode", fogDensity < 0.0 ? 9729.0 : fogDensity > 0.0 ? 2049.0 : 0.0);
        putScalar(values, "fogShape", fogDensity == 0.0 ? -1.0 : 1.0);
        putScalar(values, "alphaTestRef", irisCapturedFloat("getCurrentAlphaTest", 0.1));
        putScalar(values, "centerDepthSmooth", centerDepthSmooth);
        putScalar(values, "pi", Math.PI);
        putScalar(values, "currentColorSpace", irisColorSpace());
        int filtering = textureFilteringMode(client);
        putScalar(values, "textureFilteringMode", filtering);
        putScalar(values, "anisotropicFiltering", filtering == 2
                ? invokeDouble(client.options, "maxAnisotropyValue", 0.0) : 0.0);
        putScalar(values, "heavyFog", isHeavyFog() ? 1.0 : 0.0);
        putScalar(values, "seaLevel", invokeDouble(level, "getSeaLevel", 63.0));
        putScalar(values, "cloudTime", irisCapturedFloat("getCloudTime", 0.0));
        double fadeMillis = optionDouble(client, "chunkSectionFadeInTime", 0.0) * 1000.0;
        putScalar(values, "chunkFadeTimeInv", fadeMillis > 0.0 ? 1.0 / fadeMillis : 0.0);

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
            putMatrix3Components(values, "iris_NormalMatrix", snapshot.normalMatrix());
            putMatrix3Components(values, "iris_NormalMat", snapshot.normalMatrix());
            putMatrixComponents(values, "iris_ModelViewMatrix", snapshot.modelView(), 4);
            putMatrixComponents(values, "iris_ProjectionMatrix", snapshot.projection(), 4);
            putMatrixComponents(values, "iris_ModelViewMat", snapshot.modelView(), 4);
            putMatrixComponents(values, "iris_ProjMat", snapshot.projection(), 4);
            putMatrixComponents(values, "u_ModelViewProjectionMatrix",
                    modelViewProjection.get(new float[16]), 4);
            org.joml.Vector3f up = new org.joml.Matrix4f().set(snapshot.modelView())
                    .rotateY((float) -Math.PI / 2.0f)
                    .transformDirection(0.0f, 100.0f, 0.0f, new org.joml.Vector3f());
            putVector3(values, "upPosition", up.x, up.y, up.z);
            putCelestialPositionComponents(values, "sunPosition", snapshot.modelView(),
                    celestialAngle, 100.0f, sunPathRotation);
            putCelestialPositionComponents(values, "moonPosition", snapshot.modelView(),
                    (float) Math.toRadians(moonDegrees), 100.0f, sunPathRotation);
            double sx = values.get("sunPosition.x");
            double sy = values.get("sunPosition.y");
            double sz = values.get("sunPosition.z");
            double mx = values.get("moonPosition.x");
            double my = values.get("moonPosition.y");
            double mz = values.get("moonPosition.z");
            putVector3(values, "shadowLightPosition",
                    isDay ? sx : mx, isDay ? sy : my, isDay ? sz : mz);
            putVector3(values, "eyePosition", eyePosition(cameraEntity, snapshot));
            putVector3(values, "relativeEyePosition",
                    snapshot.cameraX() - values.get("eyePosition.x"),
                    snapshot.cameraY() - values.get("eyePosition.y"),
                    snapshot.cameraZ() - values.get("eyePosition.z"));
            putVector3(values, "playerLookVector", entityVector(cameraEntity, "getViewVector", 0.0f, 0.0f, -1.0f));
            putVector3(values, "playerBodyVector", entityVector(cameraEntity, "getForward", 0.0f, 0.0f, -1.0f));
            Object playerVehicle = invokeObject(player, "getVehicle");
            putVector3(values, "vehicleLookVector", entityVector(playerVehicle, "getForward", 0.0f, 0.0f, -1.0f));
            double[] vehiclePosition = entityPosition(playerVehicle);
            putVector3(values, "relativeVehiclePosition",
                    snapshot.cameraX() - vehiclePosition[0],
                    snapshot.cameraY() - vehiclePosition[1],
                    snapshot.cameraZ() - vehiclePosition[2]);
            putVector3(values, "skyColor", skyColor(level, snapshot));
            putVector3(values, "fogColor", fogColor(snapshot));
            putVector4(values, "iris_FogColor", new double[]{values.get("fogColor.x"),
                    values.get("fogColor.y"), values.get("fogColor.z"), 1.0});
            putVector4(values, "lightningBoltPosition", lightningBoltPosition(level, snapshot));
            putVector3(values, "endFlashPosition", endFlashPosition(level, snapshot));
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
                "gtextureId", "anisotropicFiltering", "pi", "chunkFadeTimeInv", "seaLevel",
                "cloudTime", "heavyFog", "iris_FogStart", "iris_FogEnd", "iris_FogDensity",
                "iris_TextureScale", "iris_GlintAlpha", "iris_ModelScale", "iris_ModelOffset"
        };
        for (String name : scalars) values.putIfAbsent(name, 0.0);
        values.put("maxPlayerHealth", 20.0);
        values.put("maxPlayerHunger", 20.0);
        values.put("maxPlayerArmor", 50.0);
        values.put("maxPlayerAir", 300.0);
        values.put("chunkFadeTimeInv", 0.0);
        values.put("seaLevel", 63.0);
        values.put("cloudTime", 0.0);
        values.put("iris_TextureScale", 1.0);
        values.put("iris_GlintAlpha", 1.0);
        values.put("iris_ModelScale", 1.0);
        values.put("iris_ModelOffset", 0.0);
        values.put("near", 0.05);
        values.put("far", 512.0);
        values.put("aspectRatio", 1.0);
        values.put("viewWidth", 1.0);
        values.put("viewHeight", 1.0);
        values.put("frameTime", 1.0 / 60.0);
        values.put("dhNearPlane", 0.05);
        values.put("dhFarPlane", 512.0);
        values.put("dhRenderDistance", 512.0);
        values.put("alphaTestRef", 0.1);
        values.put("pi", Math.PI);
        putVector2(values, "eyeBrightness", 0.0, 0.0);
        putVector2(values, "eyeBrightnessSmooth", 0.0, 0.0);
        putVector2(values, "atlasSize", 1.0, 1.0);
        putVector2(values, "gtextureSize", 1.0, 1.0);
        putVector2(values, "u_TextureScale", 1.0, 1.0);
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
        putVector3(values, "moonPosition", 0.0, 100.0, 0.0);
        putVector3(values, "shadowLightPosition", 0.0, 100.0, 0.0);
        putVector3(values, "skyColor", 0.0, 0.0, 0.0);
        putVector3(values, "fogColor", 0.0, 0.0, 0.0);
        putVector3(values, "endFlashPosition", 0.0, 0.0, 0.0);
        putVector3(values, "currentSelectedBlockPos", -256.0, -256.0, -256.0);
        putVector3(values, "chunkOffset", 0.0, 0.0, 0.0);
        putVector3(values, "heldBlockLightColor", 1.0, 1.0, 1.0);
        putVector3(values, "heldBlockLightColor2", 1.0, 1.0, 1.0);
        putVector3(values, "iris_ModelScale", 1.0, 1.0, 1.0);
        putVector3(values, "iris_ModelOffset", 0.0, 0.0, 0.0);
        putVector3(values, "iris_CameraTranslation", 0.0, 0.0, 0.0);
        putVector3(values, "u_RegionOffset", 0.0, 0.0, 0.0);
        putVector3(values, "u_ModelScale", 1.0, 1.0, 1.0);
        putVector4(values, "lightningBoltPosition", 0.0, 0.0, 0.0, 0.0);
        putVector4(values, "entityColor", 0.0, 0.0, 0.0, 0.0);
        putVector4(values, "iris_FogColor", 0.0, 0.0, 0.0, 0.0);
        putVector4(values, "iris_ColorModulator", 1.0, 1.0, 1.0, 1.0);
        putVector4(values, "blendFunc", 1.0, 0.0, 1.0, 0.0);
        String[] matrices = {
                "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse",
                "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse",
                "gbufferPreviousModelView", "gbufferPreviousProjection", "gl_ModelViewMatrix",
                "gl_ModelViewMatrixInverse", "gl_ProjectionMatrix", "gl_ProjectionMatrixInverse",
                "gl_ModelViewProjectionMatrix", "gl_TextureMatrix[0]",
                "iris_ModelViewMatrix", "iris_ProjectionMatrix", "u_ModelViewProjectionMatrix",
                "dhProjection", "dhProjectionInverse", "dhPreviousProjection",
                "dhPreviousProjectionInverse", "iris_TextureMat", "iris_ModelViewMat", "iris_ProjMat"
        };
        for (String name : matrices) {
            float[] identity = new org.joml.Matrix4f().get(new float[16]);
            putMatrixComponents(values, name, identity, 4);
        }
        putMatrix3Components(values, "gl_NormalMatrix", new org.joml.Matrix3f().get(new float[9]));
        putMatrix3Components(values, "iris_NormalMatrix", new org.joml.Matrix3f().get(new float[9]));
        putMatrix3Components(values, "iris_NormalMat", new org.joml.Matrix3f().get(new float[9]));
        values.put("CAT_NONE", 0.0);
        String[] categories = {"TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA",
                "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP",
                "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND"};
        for (int i = 0; i < categories.length; i++) values.put("CAT_" + categories[i], (double) (i + 1));
        values.put("PPT_NONE", 0.0);
        values.put("PPT_RAIN", 1.0);
        values.put("PPT_SNOW", 2.0);
        values.put("BIOME_PALE_GARDEN", -1.0);
        values.put("BIOME_NETHER_WASTES", -1.0);
        values.put("BIOME_CRIMSON_FOREST", -1.0);
        values.put("BIOME_WARPED_FOREST", -1.0);
        values.put("BIOME_BASALT_DELTAS", -1.0);
        values.put("BIOME_SOUL_SAND_VALLEY", -1.0);
        values.put("BIOME_SULFUR_CAVES", -1.0);
        values.put("IS_IRIS", 1.0);
        values.put("MC_VERSION", 12111.0);
    }

    private static void putDimensionUniforms(
            java.util.Map<String, Double> values, Object level, Minecraft client) {
        Object dimension = invokeObject(level, "dimensionType");
        if (dimension != null && invokeObject(dimension, "value") != null) {
            dimension = invokeObject(dimension, "value");
        }
        putScalar(values, "ambientLight", invokeDouble(dimension, "ambientLight", 0.0));
        putScalar(values, "hasCeiling", invokeBoolean(dimension, "hasCeiling", false) ? 1.0 : 0.0);
        putScalar(values, "hasSkylight", invokeBoolean(dimension, "hasSkyLight", true) ? 1.0 : 0.0);
        putScalar(values, "bedrockLevel", invokeDouble(dimension, "minY",
                invokeDouble(level, "getMinY", 0.0)));
        double height = invokeDouble(dimension, "height",
                invokeDouble(level, "getMaxY", 384.0) - invokeDouble(level, "getMinY", 0.0));
        putScalar(values, "heightLimit", height);
        putScalar(values, "logicalHeightLimit", invokeDouble(dimension, "logicalHeight", height));
        putScalar(values, "cloudHeight", attributeNumber(client, "CLOUD_HEIGHT", 192.0));
    }

    private static void putBiomeIdentity(java.util.Map<String, Double> values, Object biomeHolder) {
        if (biomeHolder == null) return;
        try {
            Object keyOptional = invokeObject(biomeHolder, "unwrapKey");
            Object resourceKey = keyOptional instanceof java.util.Optional<?> optional ? optional.orElse(null) : null;
            Object location = invokeObject(resourceKey, "location");
            String path = String.valueOf(invokeObject(location, "getPath"))
                    .toLowerCase(java.util.Locale.ROOT);
            // Iris uses a pack-specific biome ID map. CAUSTIQ currently supplies a stable
            // equality ID for the active biome and resolves all registered BIOME_* symbols.
            Object registryAccess = invokeObject(Minecraft.getInstance().level, "registryAccess");
            Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
            Object biomeRegistryKey = registries.getField("BIOME").get(null);
            Object registry = invokeCompatible(registryAccess, "registryOrThrow", biomeRegistryKey);
            if (registry instanceof Iterable<?> entries) {
                for (Object registered : entries) {
                    Object registeredKey = invokeCompatible(registry, "getKey", registered);
                    String registeredPath = String.valueOf(invokeObject(registeredKey, "getPath"));
                    Object idValue = invokeCompatible(registry, "getId", registered);
                    if (idValue instanceof Number id) {
                        values.put("BIOME_" + registeredPath.toUpperCase(java.util.Locale.ROOT),
                                (double) id.intValue());
                    }
                }
            }
            Object currentBiome = invokeObject(biomeHolder, "value");
            Object currentId = invokeCompatible(registry, "getId", currentBiome);
            int id = currentId instanceof Number number ? number.intValue() : 0;
            putScalar(values, "biome", id);
            putScalar(values, "biome_category", biomeCategory(biomeHolder, path));
            if (path.equals("pale_garden")) values.put("BIOME_PALE_GARDEN", (double) id);
            Object precipitation = precipitationAt(Minecraft.getInstance().level,
                    Minecraft.getInstance().player);
            putScalar(values, "biome_precipitation", precipitationCode(precipitation));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep the complete, typed default set when registry state is unavailable.
        }
    }

    private static int biomeCategory(Object holder, String path) {
        if (holderHasBiomeTag(holder, BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) return 0;
        if (holderHasBiomeTag(holder, BiomeTags.HAS_VILLAGE_SNOWY)) return 7;
        if (holderHasBiomeTag(holder, BiomeTags.IS_HILL)) return 2;
        if (holderHasBiomeTag(holder, BiomeTags.IS_TAIGA)) return 1;
        if (holderHasBiomeTag(holder, BiomeTags.IS_OCEAN)) return 11;
        if (holderHasBiomeTag(holder, BiomeTags.IS_JUNGLE)) return 3;
        if (holderHasBiomeTag(holder, BiomeTags.IS_FOREST)) return 10;
        if (holderHasBiomeTag(holder, BiomeTags.IS_BADLANDS)) return 4;
        if (holderHasBiomeTag(holder, BiomeTags.IS_NETHER)) return 16;
        if (holderHasBiomeTag(holder, BiomeTags.IS_END)) return 8;
        if (holderHasBiomeTag(holder, BiomeTags.IS_BEACH)) return 9;
        if (holderHasBiomeTag(holder, BiomeTags.HAS_DESERT_PYRAMID)) return 12;
        if (holderHasBiomeTag(holder, BiomeTags.IS_RIVER)) return 13;
        if (holderHasBiomeTag(holder, BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS)) return 14;
        if (holderHasBiomeTag(holder, BiomeTags.WITHOUT_ZOMBIE_SIEGES)) return 15;
        if (holderHasBiomeTag(holder, BiomeTags.IS_MOUNTAIN)) return 17;
        return path.contains("plains") ? 5 : 5;
    }

    private static boolean holderHasBiomeTag(Object holder, Object tag) {
        try {
            Object result = invokeCompatible(holder, "is", tag);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Object currentBiome(Object level, Object player) {
        if (level == null || player == null) return null;
        try {
            Object position = invokeObject(player, "blockPosition");
            return invokeCompatible(level, "getBiome", position);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object precipitationAt(Object level, Object player) {
        if (level == null || player == null) return null;
        try {
            Object position = invokeObject(player, "blockPosition");
            Object holder = invokeCompatible(level, "getBiome", position);
            Object biome = invokeObject(holder, "value");
            Object seaLevel = invokeObject(level, "getSeaLevel");
            return invokeCompatible(biome, "getPrecipitationAt", position, seaLevel);
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

    private static int eyeBrightness(Object level, Object cameraEntity, boolean sky) {
        if (level == null || cameraEntity == null) return 0;
        try {
            Object position = invokeObject(cameraEntity, "blockPosition");
            Object feet = invokeObject(cameraEntity, "position");
            double x = component(feet, "x");
            double y = invokeDouble(cameraEntity, "getEyeY", component(feet, "y"));
            double z = component(feet, "z");
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
            Object eyeBlockPosition = blockPos.getMethod("containing", double.class, double.class, double.class)
                    .invoke(null, x, y, z);
            if (eyeBlockPosition != null) position = eyeBlockPosition;
            Class<?> lightLayer = Class.forName("net.minecraft.world.level.LightLayer");
            Object layer = lightLayer.getField(sky ? "SKY" : "BLOCK").get(null);
            Object brightness = invokeCompatible(level, "getBrightness", layer, position);
            return brightness instanceof Number number ? number.intValue() * 16 : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static Object activeEffect(Object entity, String effectPath) {
        if (entity == null) return null;
        Object effects = invokeObject(entity, "getActiveEffects");
        if (!(effects instanceof Iterable<?> iterable)) return null;
        for (Object instance : iterable) {
            Object holder = invokeObject(instance, "getEffect");
            Object effect = invokeObject(holder, "value");
            if (effect == null) effect = holder;
            try {
                Object key = BuiltInRegistries.MOB_EFFECT.getKey((net.minecraft.world.effect.MobEffect) effect);
                if (key != null && effectPath.equals(invokeObject(key, "getPath"))) return instance;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static double blindnessFactor(Object entity) {
        Object effect = activeEffect(entity, "blindness");
        if (effect == null) return 0.0;
        if (invokeBoolean(effect, "isInfiniteDuration", false)) return 1.0;
        return clamp01(invokeDouble(effect, "getDuration", 0.0) / 20.0);
    }

    private static double darknessFactor(Object entity) {
        Object effect = activeEffect(entity, "darkness");
        if (effect == null) return 0.0;
        Object value = invokeCompatibleQuietly(effect, "getBlendFactor", entity, 1.0f);
        return value instanceof Number number ? clamp01(number.doubleValue()) : 0.0;
    }

    private static double nightVisionFactor(Minecraft client, Object entity) {
        if (entity != null) {
            try {
                Class<?> gameRenderer = Class.forName("net.minecraft.client.renderer.GameRenderer");
                for (Method method : gameRenderer.getMethods()) {
                    if (method.getName().equals("getNightVisionScale") && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            && method.getParameterCount() == 2) {
                        Object value = method.invoke(null, entity, 1.0f);
                        if (value instanceof Number number) return clamp01(number.doubleValue());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        Object player = client.player;
        return activeEffect(player, "night_vision") != null
                || activeEffect(player, "conduit_power") != null ? 1.0 : 0.0;
    }

    private static double irisCapturedFloat(String methodName, double fallback) {
        try {
            Class<?> stateType = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = stateType.getField("INSTANCE").get(null);
            return invokeDouble(state, methodName, fallback);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean isSurvivalPlayer(Minecraft client) {
        Object playerMode = invokeObject(client.gameMode, "getPlayerMode");
        return invokeBoolean(playerMode, "isSurvival", false);
    }

    private static boolean vehicleInWater(Object player) {
        Object vehicle = invokeObject(player, "getVehicle");
        return invokeBoolean(vehicle, "isInShallowWater", false);
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
        if (selected instanceof Boolean result) return result;
        Object fieldValue = readField(client.options, option);
        return fieldValue instanceof Boolean result ? result : fallback;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
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

    private static Object invokeCompatibleQuietly(Object target, String name, Object... arguments) {
        try { return invokeCompatible(target, name, arguments); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static int invokeIntField(Object target, String fieldName, int fallback) {
        if (target == null) return fallback;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                return value instanceof Number number ? number.intValue() : fallback;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }


    private static boolean isTaaEnabled() {
        return isEnabledDefine("TAA");
    }

    private static boolean isEnabledDefine(String name) {
        String value = ShaderRuntime.selectedDefine(name, "false");
        return "true".equalsIgnoreCase(value) || "1".equals(value)
                || "on".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static synchronized float wetnessForFrame(float rain, int frameCounter) {
        if (wetnessFrame != frameCounter) {
            wetnessForCurrentFrame = (float) smoothUniform("wetness", clamp01(rain), 600.0, 200.0);
            wetnessFrame = frameCounter;
        }
        return wetnessForCurrentFrame;
    }

    private static int[] currentViewportSize() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            return new int[]{Math.max(0, viewport.get(2)), Math.max(0, viewport.get(3))};
        }
    }

    /** Capture center depth asynchronously after Nvidium's opaque terrain pass. */
    public static void captureCenterDepth() {
        if (centerDepthCaptureDisabled || Minecraft.getInstance().level == null) return;
        try {
            pollCenterDepthReadbacks();
            int[] viewport = new int[4];
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.IntBuffer values = stack.mallocInt(4);
                GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, values);
                for (int i = 0; i < 4; i++) viewport[i] = values.get(i);
            }
            if (viewport[2] <= 0 || viewport[3] <= 0) return;

            int slot = -1;
            for (int i = 0; i < CENTER_DEPTH_PBOS.length; i++) {
                int candidate = (centerDepthWriteIndex + i) % CENTER_DEPTH_PBOS.length;
                if (CENTER_DEPTH_FENCES[candidate] == 0L) {
                    slot = candidate;
                    break;
                }
            }
            if (slot < 0) return;

            int previousPackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                if (CENTER_DEPTH_PBOS[slot] == 0) CENTER_DEPTH_PBOS[slot] = GL15C.glGenBuffers();
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, CENTER_DEPTH_PBOS[slot]);
                GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, Float.BYTES, GL15C.GL_STREAM_READ);
                GL11C.glReadPixels(viewport[0] + viewport[2] / 2, viewport[1] + viewport[3] / 2,
                        1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, 0L);
                long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (fence == 0L) throw new IllegalStateException("Could not fence asynchronous center-depth readback.");
                CENTER_DEPTH_FENCES[slot] = fence;
                CENTER_DEPTH_SEQUENCES[slot] = ++centerDepthSequence;
                centerDepthWriteIndex = (slot + 1) % CENTER_DEPTH_PBOS.length;
            } finally {
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            }
        } catch (RuntimeException exception) {
            centerDepthCaptureDisabled = true;
            if (!warnedCenterDepthFailure) {
                warnedCenterDepthFailure = true;
                CaustiqClient.LOGGER.warn("Center-depth sampling is unavailable; retaining the last valid centerDepthSmooth value.", exception);
            }
        }
    }

    private static void pollCenterDepthReadbacks() {
        int previousPackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        float latestValue = Float.NaN;
        long latestSequence = -1L;
        try {
            for (int i = 0; i < CENTER_DEPTH_FENCES.length; i++) {
                long fence = CENTER_DEPTH_FENCES[i];
                if (fence == 0L) continue;
                int status = GL32C.glClientWaitSync(fence, 0, 0L);
                if (status == GL32C.GL_WAIT_FAILED) {
                    GL32C.glDeleteSync(fence);
                    CENTER_DEPTH_FENCES[i] = 0L;
                    CENTER_DEPTH_SEQUENCES[i] = 0L;
                    continue;
                }
                if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) continue;
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, CENTER_DEPTH_PBOS[i]);
                java.nio.ByteBuffer mapped = GL15C.glMapBuffer(GL21C.GL_PIXEL_PACK_BUFFER, GL15C.GL_READ_ONLY);
                if (mapped != null) {
                    float sample = mapped.order(java.nio.ByteOrder.nativeOrder()).getFloat(0);
                    GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
                    if (Float.isFinite(sample) && sample >= 0.0f && sample <= 1.0f
                            && CENTER_DEPTH_SEQUENCES[i] > latestSequence) {
                        latestValue = sample;
                        latestSequence = CENTER_DEPTH_SEQUENCES[i];
                    }
                }
                GL32C.glDeleteSync(fence);
                CENTER_DEPTH_FENCES[i] = 0L;
                CENTER_DEPTH_SEQUENCES[i] = 0L;
            }
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        }
        if (Float.isFinite(latestValue)) {
            centerDepthSmooth = (float) smoothUniform("centerDepthSmooth", latestValue, 1.0, 1.0);
        }
    }

    /** Releases asynchronous depth-readback resources while the GL context is current. */
    public static void destroy() {
        for (int i = 0; i < CENTER_DEPTH_PBOS.length; i++) {
            if (CENTER_DEPTH_FENCES[i] != 0L) GL32C.glDeleteSync(CENTER_DEPTH_FENCES[i]);
            if (CENTER_DEPTH_PBOS[i] != 0) GL15C.glDeleteBuffers(CENTER_DEPTH_PBOS[i]);
            CENTER_DEPTH_FENCES[i] = 0L;
            CENTER_DEPTH_PBOS[i] = 0;
            CENTER_DEPTH_SEQUENCES[i] = 0L;
        }
        centerDepthWriteIndex = 0;
        centerDepthSequence = 0L;
        centerDepthSmooth = 1.0f;
        centerDepthCaptureDisabled = false;
        warnedCenterDepthFailure = false;
        SMOOTHED_UNIFORMS.remove("centerDepthSmooth");
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record SmoothedUniform(double value, long timestampNanos) { }
    private static final java.util.concurrent.ConcurrentMap<String, SmoothedUniform> SMOOTHED_UNIFORMS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static double smoothUniform(String key, double target,
                                        double riseHalfLifeTicks, double fallHalfLifeTicks) {
        long now = System.nanoTime();
        SmoothedUniform previous = SMOOTHED_UNIFORMS.get(key);
        if (previous == null || !Double.isFinite(previous.value())) {
            SMOOTHED_UNIFORMS.put(key, new SmoothedUniform(target, now));
            return target;
        }
        double elapsedTicks = Math.max(0.0, (now - previous.timestampNanos()) / 50_000_000.0);
        double halfLife = target >= previous.value() ? riseHalfLifeTicks : fallHalfLifeTicks;
        double alpha = halfLife <= 0.0 ? 1.0
                : 1.0 - Math.exp(-Math.log(2.0) * elapsedTicks / halfLife);
        double result = previous.value() + (target - previous.value()) * alpha;
        SMOOTHED_UNIFORMS.put(key, new SmoothedUniform(result, now));
        return result;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
        Object colorValue = environmentAttribute(Minecraft.getInstance(), "SKY_COLOR");
        if (colorValue instanceof Number number) {
            int argb = number.intValue();
            return new double[]{
                    ((argb >> 16) & 0xff) / 255.0,
                    ((argb >> 8) & 0xff) / 255.0,
                    (argb & 0xff) / 255.0
            };
        }
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

    private static void putVector4(java.util.Map<String, Double> values, String name, double[] components) {
        putVector4(values, name,
                components.length > 0 ? components[0] : 0.0,
                components.length > 1 ? components[1] : 0.0,
                components.length > 2 ? components[2] : 0.0,
                components.length > 3 ? components[3] : 0.0);
    }

    private static void putVector3(java.util.Map<String, Double> values, String name, double[] components) {
        putVector3(values, name,
                components.length > 0 ? components[0] : 0.0,
                components.length > 1 ? components[1] : 0.0,
                components.length > 2 ? components[2] : 0.0);
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
        putCelestialPositionComponents(values, "sunPosition", modelView, sunAngle, 100.0f, 0.0f);
    }

    private static org.joml.Vector3f celestialDirection(
            float[] modelView, double angleDegrees, float sunPathRotationDegrees) {
        org.joml.Matrix4f celestial = new org.joml.Matrix4f()
                .rotationY((float) -Math.PI / 2.0f)
                .rotateZ((float) Math.toRadians(sunPathRotationDegrees))
                .rotateX((float) Math.toRadians(angleDegrees));
        return new org.joml.Matrix4f().set(modelView).mul(celestial)
                .transformDirection(0.0f, 1.0f, 0.0f, new org.joml.Vector3f()).normalize();
    }

    private static void putCelestialPositionComponents(java.util.Map<String, Double> values,
                                                       String name, float[] modelView,
                                                       float angleRadians, float height,
                                                       float sunPathRotationDegrees) {
        org.joml.Matrix4f celestial = new org.joml.Matrix4f()
                .rotationY((float) -Math.PI / 2.0f)
                .rotateZ((float) Math.toRadians(sunPathRotationDegrees))
                .rotateX(angleRadians);
        org.joml.Vector3f position = new org.joml.Matrix4f()
                .set(modelView)
                .mul(celestial)
                .transformPosition(0.0f, height, 0.0f, new org.joml.Vector3f());
        putVector3(values, name, position.x, position.y, position.z);
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
        lastCaptureNanos = 0L;
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
            float frameTime,
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
