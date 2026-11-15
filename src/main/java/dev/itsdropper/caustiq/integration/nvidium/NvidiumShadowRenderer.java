package dev.itsdropper.caustiq.integration.nvidium;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.itsdropper.caustiq.CaustiqClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4i;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.NVRepresentativeFragmentTest;
import org.lwjgl.opengl.NVUniformBufferUnifiedMemory;
import org.lwjgl.opengl.NVVertexBufferUnifiedMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Independent light-space terrain renderer for Nvidium.
 *
 * <p>The renderer deliberately owns its scene UBO, visibility buffers, section
 * indices and indirect command buffer. It shares only Nvidium's immutable
 * region/section metadata and terrain arena. This is essential: Nvidium's
 * normal command buffer is camera-culled and is consumed by the next frame.</p>
 *
 * <p>Its first implementation renders Nvidium's native opaque/cutout terrain
 * shader into a depth-only target. Iris shadow-program translation and shadow
 * uniform exposure are layered on top by the shader-pack runtime.</p>
 */
public final class NvidiumShadowRenderer {
    private static final int DEFAULT_RESOLUTION = 2048;
    private static final float DEFAULT_DISTANCE = 128.0f;
    private static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    private static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;
    private static final ThreadLocal<Boolean> SHADOW_RASTERIZATION =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<Object, State> STATES = new IdentityHashMap<>();

    private static volatile boolean enabled;
    private static volatile int requestedResolution = DEFAULT_RESOLUTION;
    private static volatile float requestedDistance = DEFAULT_DISTANCE;
    private static volatile State activeState;
    private static boolean failureLogged;

    private NvidiumShadowRenderer() { }

    /** Enables the isolated shadow path only when the selected shader pack needs it. */
    public static void configure(boolean shouldRender, int resolution, float distance) {
        enabled = shouldRender;
        requestedResolution = Math.max(256, Math.min(8192, Integer.highestOneBit(Math.max(256, resolution))));
        requestedDistance = Float.isFinite(distance) ? Math.max(16.0f, Math.min(512.0f, distance))
                : DEFAULT_DISTANCE;
        failureLogged = false;
        if (!shouldRender) {
            for (State state : STATES.values()) state.close();
            STATES.clear();
            activeState = null;
        }
    }

    public static boolean isShadowRasterization() { return SHADOW_RASTERIZATION.get(); }

    static boolean enabledForTests() { return enabled; }
    static int requestedResolutionForTests() { return requestedResolution; }
    static float requestedDistanceForTests() { return requestedDistance; }

    /** Called at the tail of NvidiumWorldRenderer.renderFrame, after camera terrain submission. */
    public static void render(Object worldRenderer, Object terrainPass, Object fogParameters,
                              double cameraX, double cameraY, double cameraZ, Object terrainSampler) {
        if (!enabled || worldRenderer == null || terrainPass == null || terrainSampler == null) return;
        try {
            State state = stateFor(worldRenderer);
            state.ensureResolution(requestedResolution);
            int regionCount = state.uploadScene(terrainPass, fogParameters, cameraX, cameraY, cameraZ,
                    requestedDistance);
            if (regionCount == 0) {
                if (NvidiumTerrainPassBridge.shouldClearAttachments(terrainPass)) {
                    state.clearDepth();
                    state.depthValid = true;
                    activeState = state;
                }
                return;
            }
            boolean clear = NvidiumTerrainPassBridge.shouldClearAttachments(terrainPass) || !state.depthValid;
            activeState = state;
            renderShadowPass(state, terrainPass, terrainSampler, regionCount, clear);
            state.depthValid = true;
            activeState = state;
            failureLogged = false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!failureLogged) {
                failureLogged = true;
                CaustiqClient.LOGGER.error(
                        "CAUSTIQ's isolated Nvidium shadow terrain pipeline failed; normal Nvidium rendering is left intact.",
                        unwrap(exception));
            }
            State failed = STATES.remove(worldRenderer);
            if (failed != null) {
                if (activeState == failed) activeState = null;
                try { failed.close(); }
                catch (RuntimeException cleanupFailure) {
                    CaustiqClient.LOGGER.warn("Unable to release the failed Nvidium shadow pipeline.", cleanupFailure);
                }
            }
            enabled = false;
        }
    }

    /** Shadow depth texture exposed to Iris shadowtex samplers. */
    public static int shadowTexture() {
        State state = activeState;
        return state == null || (!state.depthValid && !SHADOW_RASTERIZATION.get())
                ? 0 : state.depthTexture;
    }

    /** Iris shadowcolor0 attachment written by the shadow terrain fragment program. */
    public static int shadowColorTexture() {
        State state = activeState;
        return state == null || !state.depthValid ? 0 : state.shadowColorTexture;
    }

    public static int shadowResolution() {
        State state = activeState;
        return state == null ? 0 : state.resolution;
    }

    public static float[] shadowModelView() {
        State state = activeState;
        return state == null ? new Matrix4f().identity().get(new float[16]) : state.shadowModelView.clone();
    }

    public static float[] shadowProjection() {
        State state = activeState;
        return state == null ? new Matrix4f().identity().get(new float[16]) : state.shadowProjection.clone();
    }

    public static boolean isAvailable() {
        State state = activeState;
        return state != null && state.depthTexture != 0 && state.depthValid;
    }

    /** Nvidium reloads its native rasterizer objects when its shader settings change. */
    public static void reload(Object worldRenderer) {
        State state = STATES.get(worldRenderer);
        if (state == null) return;
        state.deleteShadowRasterizer();
        state.createShadowRasterizer();
    }

    /** Releases every resource owned by this renderer; called on Nvidium renderer destruction. */
    public static void destroy(Object worldRenderer) {
        State state = STATES.remove(worldRenderer);
        if (state == null) return;
        if (activeState == state) activeState = null;
        state.close();
    }

    static void resetForTests() {
        enabled = false;
        requestedResolution = DEFAULT_RESOLUTION;
        requestedDistance = DEFAULT_DISTANCE;
        failureLogged = false;
        activeState = null;
        SHADOW_RASTERIZATION.remove();
        for (State state : STATES.values()) state.close();
        STATES.clear();
    }

    private static State stateFor(Object worldRenderer) throws ReflectiveOperationException {
        State existing = STATES.get(worldRenderer);
        if (existing != null) return existing;
        State created = new State(worldRenderer);
        STATES.put(worldRenderer, created);
        return created;
    }

    private static void renderShadowPass(State state, Object terrainPass, Object terrainSampler,
                                         int regionCount, boolean clear) throws ReflectiveOperationException {
        GlState previous = GlState.capture();
        try {
            state.bindDepthTarget();
            GL11C.glViewport(0, 0, state.resolution, state.resolution);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            GL11C.glDepthMask(true);
            GL11C.glColorMask(false, false, false, false);
            if (clear) {
                GL11C.glClearDepth(1.0);
                GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            }

            clearBuffer(state.regionVisibility);
            clearBuffer(state.sectionVisibility);
            clearUIntBuffer(state.statisticsBuffer);
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);

            enableUnifiedBuffers();
            NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                    NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV, 0,
                    address(state.sceneUniform), state.sceneSize);

            // Region and section AABBs are rasterized against a cleared depth
            // buffer. They are clipped by the light-space MVP, not the camera
            // viewport; every occupied region was inserted into the private
            // region-index list.
            GL11C.glDepthMask(false);
            GL11C.glEnable(NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            invoke(state.regionRasterizer, "raster", regionCount);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            invoke(state.sectionRasterizer, "raster", regionCount);
            GL11C.glDisable(NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            invoke(state.commandBuilder, "dispatch", regionCount);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);

            // The shadow rasterizer is a separate native Nvidium primary
            // program. It retains Nvidium's atlas alpha test but deliberately
            // bypasses CAUSTIQ's gbuffer shader adapter.
            GL11C.glDepthMask(true);
            SHADOW_RASTERIZATION.set(Boolean.TRUE);
            try {
                invoke(state.shadowRasterizer, "raster",
                        terrainPass, regionCount, address(state.terrainCommands), terrainSampler);
            } finally {
                SHADOW_RASTERIZATION.remove();
            }
            GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        } finally {
            SHADOW_RASTERIZATION.remove();
            try {
                state.restoreNvidiumBindings();
            } catch (ReflectiveOperationException | RuntimeException restoreFailure) {
                CaustiqClient.LOGGER.warn("Could not restore Nvidium's scene and indirect-buffer bindings after its shadow pass.", restoreFailure);
            }
            disableUnifiedBuffers();
            previous.restore();
        }
    }

    private static void enableUnifiedBuffers() {
        GL11.glEnableClientState(
                NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV);
        GL11.glEnableClientState(
                NVVertexBufferUnifiedMemory.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        GL11.glEnableClientState(
                NVVertexBufferUnifiedMemory.GL_ELEMENT_ARRAY_UNIFIED_NV);
        GL11.glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
    }

    private static void disableUnifiedBuffers() {
        GL11.glDisableClientState(
                NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV);
        GL11.glDisableClientState(
                NVVertexBufferUnifiedMemory.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        GL11.glDisableClientState(
                NVVertexBufferUnifiedMemory.GL_ELEMENT_ARRAY_UNIFIED_NV);
        GL11.glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
    }

    private static void clearBuffer(Object buffer) throws ReflectiveOperationException {
        int id = ((Number) invoke(buffer, "getId")).intValue();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer zero = stack.calloc(1);
            GL45C.glClearNamedBufferData(id, GL30C.GL_R8UI,
                    GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_BYTE, zero);
        }
    }

    private static void clearUIntBuffer(Object buffer) throws ReflectiveOperationException {
        int id = ((Number) invoke(buffer, "getId")).intValue();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer zero = stack.calloc(4);
            GL45C.glClearNamedBufferData(id, GL30C.GL_R32UI,
                    GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, zero);
        }
    }

    private static long address(Object buffer) throws ReflectiveOperationException {
        return ((Number) invoke(buffer, "getDeviceAddress")).longValue();
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class<?> clazz ? clazz : owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner instanceof Class<?> ? null : owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

    private static Object invoke(Object owner, String name, Object... arguments) throws ReflectiveOperationException {
        Method selected = null;
        for (Method method : owner.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!compatible(parameters[i], arguments[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            for (Class<?> type = owner.getClass(); type != null && selected == null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                    Class<?>[] parameters = method.getParameterTypes();
                    boolean matches = true;
                    for (int i = 0; i < parameters.length; i++) {
                        if (!compatible(parameters[i], arguments[i])) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        method.setAccessible(true);
                        selected = method;
                        break;
                    }
                }
            }
        }
        if (selected == null) throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
        try {
            return selected.invoke(owner, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    private static boolean compatible(Class<?> parameter, Object argument) {
        if (argument == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isAssignableFrom(argument.getClass());
        return (parameter == int.class && argument instanceof Integer)
                || (parameter == long.class && argument instanceof Long)
                || (parameter == float.class && argument instanceof Float)
                || (parameter == double.class && argument instanceof Double)
                || (parameter == boolean.class && argument instanceof Boolean)
                || (parameter == short.class && argument instanceof Short)
                || (parameter == byte.class && argument instanceof Byte);
    }

    private static final class State implements AutoCloseable {
        private final Object worldRenderer;
        private final Object pipeline;
        private final Object device;
        private final Object uploadStream;
        private final Object sectionManager;
        private final Object regionManager;
        private final Object sceneUniform;
        private final Object regionRasterizer;
        private final Object sectionRasterizer;
        private final Object commandBuilder;
        private final Object regionVisibility;
        private final Object sectionVisibility;
        private final Object sectionIndices;
        private final Object terrainCommands;
        private final Object translucencyCommands;
        private final Object temporalCommands;
        private final Object statisticsBuffer;
        private final int sceneSize;
        private final int maxRegions;
        private final boolean compiledForFog;
        private final int depthTexture;
        private final int framebuffer;
        private Object shadowRasterizer;
        private int resolution;
        private int frameId;
        private boolean depthValid;
        private float[] shadowModelView = new Matrix4f().identity().get(new float[16]);
        private float[] shadowProjection = new Matrix4f().identity().get(new float[16]);

        private State(Object worldRenderer) throws ReflectiveOperationException {
            this.worldRenderer = worldRenderer;
            this.pipeline = readField(worldRenderer, "renderPipeline");
            this.device = readField(pipeline, "device");
            this.uploadStream = readField(worldRenderer, "uploadStream");
            this.sectionManager = readField(pipeline, "sectionManager");
            this.regionManager = invoke(sectionManager, "getRegionManager");
            this.regionRasterizer = readField(pipeline, "regionRasterizer");
            this.sectionRasterizer = readField(pipeline, "sectionRasterizer");
            this.commandBuilder = readField(pipeline, "cmdBufferBuilder");
            this.sceneSize = ((Number) readField(pipeline.getClass(), "SCENE_SIZE")).intValue();
            this.compiledForFog = (Boolean) readField(pipeline, "compiledForFog");
            this.maxRegions = ((Number) invoke(regionManager, "maxRegions")).intValue();

            this.sceneUniform = createBuffer(sceneSize + maxRegions * 2L);
            this.regionVisibility = createBuffer(maxRegions);
            this.sectionVisibility = createBuffer(maxRegions * 256L);
            this.sectionIndices = createBuffer(maxRegions * 256L * 3L);
            this.terrainCommands = createBuffer(maxRegions * 8L);
            this.translucencyCommands = createBuffer(maxRegions * 8L);
            this.temporalCommands = createBuffer(maxRegions * 8L);
            this.statisticsBuffer = createBuffer(16L);

            int[] targets = createDepthTarget(DEFAULT_RESOLUTION);
            this.depthTexture = targets[0];
            this.framebuffer = targets[1];
            this.resolution = DEFAULT_RESOLUTION;
            createShadowRasterizer();
        }

        private Object createBuffer(long size) throws ReflectiveOperationException {
            return invoke(device, "createDeviceOnlyMappedBuffer", size);
        }

        private void createShadowRasterizer() {
            if (shadowRasterizer != null) return;
            try {
                NvidiumShaderProgramBridge.beginPipelineCompile(
                        NvidiumShaderProgramBridge.PipelineKind.SHADOW);
                try {
                    Class<?> type = Class.forName("me.cortex.nvidium.renderers.PrimaryTerrainRasterizer");
                    shadowRasterizer = type.getConstructor().newInstance();
                } finally {
                    NvidiumShaderProgramBridge.endPipelineCompile();
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw new IllegalStateException("Could not compile Nvidium's independent shadow terrain rasterizer.", exception);
            }
        }

        private void deleteShadowRasterizer() {
            if (shadowRasterizer == null) return;
            try { invoke(shadowRasterizer, "delete"); }
            catch (ReflectiveOperationException | RuntimeException exception) {
                CaustiqClient.LOGGER.warn("Unable to release Nvidium shadow rasterizer cleanly.", exception);
            }
            shadowRasterizer = null;
        }

        private void ensureResolution(int requested) {
            if (resolution == requested) return;
            int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F,
                    requested, requested, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT,
                    (ByteBuffer) null);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(activeTexture);
            resolution = requested;
            depthValid = false;
            checkFramebuffer();
        }

        private void bindDepthTarget() {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        }

        private void clearDepth() {
            GlState previous = GlState.capture();
            try {
                bindDepthTarget();
                GL11C.glViewport(0, 0, resolution, resolution);
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDepthMask(true);
                GL11C.glClearDepth(1.0);
                GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer clearColor = stack.floats(1.0f, 1.0f, 1.0f, 1.0f);
                    GL30C.glClearBufferfv(GL11C.GL_COLOR, 1, clearColor);
                }
            } finally {
                previous.restore();
            }
        }

        private int uploadScene(Object terrainPass, Object fogParameters,
                                double cameraX, double cameraY, double cameraZ, float distance)
                throws ReflectiveOperationException {
            int maxIndex = ((Number) invoke(regionManager, "maxRegionIndex")).intValue();
            long indicesSize = (long) maxIndex * 2L;
            if (indicesSize > (long) maxRegions * 2L) {
                throw new IllegalStateException("Nvidium region index range exceeds its allocated region capacity.");
            }
            long indexUpload = ((Number) invoke(uploadStream, "upload", sceneUniform,
                    (long) sceneSize, indicesSize == 0 ? 2L : indicesSize)).longValue();
            int regionCount = 0;
            int chunkX = (int) Math.floor(cameraX) >> 4;
            int chunkY = (int) Math.floor(cameraY) >> 4;
            int chunkZ = (int) Math.floor(cameraZ) >> 4;
            for (int region = 0; region < maxIndex; region++) {
                if (!((Boolean) invoke(regionManager, "regionExists", region))) continue;
                MemoryUtil.memPutShort(indexUpload + (long) regionCount * 2L, (short) region);
                regionCount++;
            }
            if (regionCount == 0) {
                invoke(uploadStream, "commit");
                return 0;
            }
            if (regionCount > 0xFFFF) {
                throw new IllegalStateException("Nvidium has more active regions than its uint16 scene ABI can address.");
            }

            long sceneUpload = ((Number) invoke(uploadStream, "upload", sceneUniform,
                    0L, (long) sceneSize)).longValue();
            MemoryUtil.memSet(sceneUpload, 0, sceneSize);

            float localX = (float) (cameraX - (chunkX << 4));
            float localY = (float) (cameraY - (chunkY << 4));
            float localZ = (float) (cameraZ - (chunkZ << 4));
            Vector3f delta = new Vector3f(localX, localY, localZ).negate();

            float sunAngle = readSunAngle();
            Vector3f lightDirection = new Vector3f(
                    (float) Math.sin(sunAngle), (float) Math.cos(sunAngle), 0.25f).normalize();
            // Keep the directional-light camera above the scene at night as
            // well; the moon illuminates from the opposite hemisphere.
            if (lightDirection.y < 0.0f) lightDirection.negate();
            Vector3f up = Math.abs(lightDirection.dot(0.0f, 1.0f, 0.0f)) > 0.97f
                    ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
            Vector3f lightEye = new Vector3f(lightDirection).mul(distance);
            Matrix4f view = new Matrix4f().lookAt(
                    lightEye.x, lightEye.y, lightEye.z,
                    0.0f, 0.0f, 0.0f,
                    up.x, up.y, up.z);
            Matrix4f projection = new Matrix4f().ortho(
                    -distance, distance, -distance, distance, 0.1f, distance * 4.0f);
            Matrix4f mvp = new Matrix4f(projection).mul(view).translate(delta);
            shadowModelView = view.get(new float[16]);
            shadowProjection = projection.get(new float[16]);

            long cursor = sceneUpload;
            mvp.getToAddress(cursor);
            cursor += 64L;
            if (compiledForFog) {
                new Matrix4f(mvp).invert().getToAddress(cursor);
                cursor += 64L;
            }
            new Vector4i(chunkX, chunkY, chunkZ, 0).getToAddress(cursor);
            cursor += 16L;
            new Vector4f(delta, 0.0f).getToAddress(cursor);
            cursor += 16L;

            long sceneAddress = address(sceneUniform);
            MemoryUtil.memPutLong(cursor, sceneAddress + sceneSize); cursor += 8L;
            MemoryUtil.memPutLong(cursor, ((Number) invoke(regionManager, "getRegionBufferAddress")).longValue()); cursor += 8L;
            MemoryUtil.memPutLong(cursor, ((Number) invoke(regionManager, "getSectionBufferAddress")).longValue()); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(regionVisibility)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(sectionVisibility)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(sectionIndices)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(terrainCommands)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(translucencyCommands)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(temporalCommands)); cursor += 8L;
            MemoryUtil.memPutLong(cursor, sceneAddress + sceneSize); cursor += 8L;

            Object terrainArena = readField(sectionManager, "terrainAreana");
            Object terrainBuffer = readField(terrainArena, "buffer");
            long terrainAddress = address(terrainBuffer);
            MemoryUtil.memPutLong(cursor, terrainAddress); cursor += 8L;
            MemoryUtil.memPutLong(cursor, terrainAddress); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(readField(pipeline, "transformationArray"))); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(readField(pipeline, "originOffsetArray"))); cursor += 8L;
            MemoryUtil.memPutLong(cursor, address(statisticsBuffer)); cursor += 8L;

            int screenWidth = ((Number) invoke(invoke(Class.forName("net.minecraft.client.Minecraft")
                    .getMethod("getInstance").invoke(null), "getWindow"), "getWidth")).intValue();
            int screenHeight = ((Number) invoke(invoke(Class.forName("net.minecraft.client.Minecraft")
                    .getMethod("getInstance").invoke(null), "getWindow"), "getHeight")).intValue();
            MemoryUtil.memPutFloat(cursor, screenWidth * 0.5f); cursor += 4L;
            MemoryUtil.memPutFloat(cursor, screenHeight * 0.5f); cursor += 4L;
            writeVector4(cursor, fogParameters, "red", "green", "blue", "alpha",
                    new float[]{0.0f, 0.0f, 0.0f, 1.0f}); cursor += 16L;
            writeVector2(cursor, fogParameters, "environmentalStart", "environmentalEnd",
                    new float[]{0.0f, 1.0f}); cursor += 8L;
            writeVector2(cursor, fogParameters, "renderStart", "renderEnd",
                    new float[]{0.0f, 1.0f}); cursor += 8L;

            int[] atlasSize = atlasSize();
            int precision = subTexelPrecisionBits();
            float texelShrink = (float) (1.0f / textureMaxValue() - (1.0 / Math.max(1, atlasSize[0])) / (1 << precision));
            float texelShrinkY = (float) (1.0f / textureMaxValue() - (1.0 / Math.max(1, atlasSize[1])) / (1 << precision));
            MemoryUtil.memPutFloat(cursor, texelShrink); cursor += 4L;
            MemoryUtil.memPutFloat(cursor, texelShrinkY); cursor += 4L;
            MemoryUtil.memPutFloat(cursor, 1.0f / Math.max(1, atlasSize[0])); cursor += 4L;
            MemoryUtil.memPutFloat(cursor, 1.0f / Math.max(1, atlasSize[1])); cursor += 4L;
            MemoryUtil.memPutInt(cursor, 0); cursor += 4L;
            MemoryUtil.memPutShort(cursor, (short) regionCount); cursor += 2L;
            MemoryUtil.memPutByte(cursor, (byte) frameId++);

            invoke(uploadStream, "commit");
            return regionCount;
        }

        private float readSunAngle() {
            try {
                Object minecraft = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
                Object level = readField(minecraft, "level");
                if (level == null) return 0.0f;
                Object angle = invoke(level, "getSunAngle", 1.0f);
                return ((Number) angle).floatValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return 0.0f;
            }
        }

        private int[] atlasSize() {
            try {
                Object minecraft = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
                Object manager = invoke(minecraft, "getTextureManager");
                Class<?> atlasType = Class.forName("net.minecraft.client.renderer.texture.TextureAtlas");
                Object location = readField(atlasType, "LOCATION_BLOCKS");
                Object atlas = invoke(manager, "getTexture", location);
                return new int[]{
                        ((Number) invoke(atlas, "getWidth")).intValue(),
                        ((Number) invoke(atlas, "getHeight")).intValue()};
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return new int[]{1024, 1024};
            }
        }

        private int subTexelPrecisionBits() {
            try {
                Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice");
                Object instance = readField(type, "INSTANCE");
                return ((Number) invoke(instance, "getSubTexelPrecisionBits")).intValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return 0;
            }
        }

        private int textureMaxValue() {
            try {
                Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex");
                return ((Number) readField(type, "TEXTURE_MAX_VALUE")).intValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return 65535;
            }
        }

        private void writeVector4(long address, Object source, String a, String b, String c, String d, float[] fallback) {
            MemoryUtil.memPutFloat(address, floatValue(source, a, fallback[0]));
            MemoryUtil.memPutFloat(address + 4L, floatValue(source, b, fallback[1]));
            MemoryUtil.memPutFloat(address + 8L, floatValue(source, c, fallback[2]));
            MemoryUtil.memPutFloat(address + 12L, floatValue(source, d, fallback[3]));
        }

        private void writeVector2(long address, Object source, String a, String b, float[] fallback) {
            MemoryUtil.memPutFloat(address, floatValue(source, a, fallback[0]));
            MemoryUtil.memPutFloat(address + 4L, floatValue(source, b, fallback[1]));
        }

        private float floatValue(Object source, String method, float fallback) {
            if (source == null) return fallback;
            try { return ((Number) invoke(source, method)).floatValue(); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return fallback; }
        }

        private void restoreNvidiumBindings() throws ReflectiveOperationException {
            Object nativeScene = readField(pipeline, "sceneUniform");
            NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                    NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV, 0,
                    address(nativeScene), sceneSize);
            int nativeRegionCount = ((Number) readField(pipeline, "prevRegionCount")).intValue();
            if (nativeRegionCount > 0) {
                Object nativeCommands = readField(pipeline, "terrainCommandBuffer");
                NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                        GL_DRAW_INDIRECT_ADDRESS_NV, 0, address(nativeCommands), nativeRegionCount * 8L);
            }
        }

        private void checkFramebuffer() {
            int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            try {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                        GL11C.GL_TEXTURE_2D, depthTexture, 0);
                GL11C.glDrawBuffer(GL11C.GL_NONE);
                GL11C.glReadBuffer(GL11C.GL_NONE);
                int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
                if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Nvidium shadow depth framebuffer is incomplete: 0x"
                            + Integer.toHexString(status));
                }
            } finally {
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            }
        }

        @Override
        public void close() {
            deleteShadowRasterizer();
            delete(sceneUniform);
            delete(regionVisibility);
            delete(sectionVisibility);
            delete(sectionIndices);
            delete(terrainCommands);
            delete(translucencyCommands);
            delete(temporalCommands);
            delete(statisticsBuffer);
            GL30C.glDeleteFramebuffers(framebuffer);
            GL11C.glDeleteTextures(depthTexture);
            depthValid = false;
        }

        private static void delete(Object resource) {
            if (resource == null) return;
            try { invoke(resource, "delete"); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }

    private static int[] createDepthTarget(int resolution) {
        int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 0x812D);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 0x812D);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer border = stack.floats(1.0f, 1.0f, 1.0f, 1.0f);
            GL11C.glTexParameterfv(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_BORDER_COLOR, border);
        }
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F,
                resolution, resolution, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT,
                (ByteBuffer) null);

        int framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, texture, 0);
        GL11C.glDrawBuffer(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
        GL13C.glActiveTexture(previousActiveTexture);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            GL30C.glDeleteFramebuffers(framebuffer);
            GL11C.glDeleteTextures(texture);
            throw new IllegalStateException("Could not create Nvidium shadow depth target: 0x"
                    + Integer.toHexString(status));
        }
        return new int[]{texture, framebuffer};
    }

    private record GlState(
            int drawFramebuffer, int readFramebuffer, int[] viewport,
            boolean depthTest, boolean blend, boolean cull, boolean scissor,
            boolean representative, int depthFunction, boolean depthMask,
            boolean[] colorMask, int activeTexture, int[] textureBindings, int[] samplerBindings,
            int program, int vertexArray, float clearDepth) {
        private static GlState capture() {
            int[] viewport = new int[4];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer values = stack.mallocInt(4);
                GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, values);
                for (int i = 0; i < 4; i++) viewport[i] = values.get(i);
            }
            boolean[] colorMask = new boolean[4];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer values = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, values);
                for (int i = 0; i < 4; i++) colorMask[i] = values.get(i) != 0;
            }
            int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            int[] textureBindings = new int[2];
            int[] samplerBindings = new int[2];
            for (int i = 0; i < 2; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
                textureBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                samplerBindings[i] = GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, i);
            }
            GL13C.glActiveTexture(active);
            return new GlState(
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    viewport,
                    GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glIsEnabled(GL11C.GL_BLEND),
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                    GL11C.glIsEnabled(NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV),
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                    GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    colorMask,
                    active, textureBindings, samplerBindings,
                    GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                    GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                    GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE));
        }

        private void restore() {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            toggle(GL11C.GL_DEPTH_TEST, depthTest);
            toggle(GL11C.GL_BLEND, blend);
            toggle(GL11C.GL_CULL_FACE, cull);
            toggle(GL11C.GL_SCISSOR_TEST, scissor);
            toggle(NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV, representative);
            GL11C.glDepthFunc(depthFunction);
            GL11C.glDepthMask(depthMask);
            GL11C.glClearDepth(clearDepth);
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(vertexArray);
            GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            for (int i = 0; i < 2; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textureBindings[i]);
                GL33C.glBindSampler(i, samplerBindings[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            // Nvidium's renderFrame contract leaves these client states disabled on return.
            GL11.glDisableClientState(NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV);
            GL11.glDisableClientState(NVVertexBufferUnifiedMemory.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
            GL11.glDisableClientState(NVVertexBufferUnifiedMemory.GL_ELEMENT_ARRAY_UNIFIED_NV);
            GL11.glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        }

        private static void toggle(int capability, boolean enabled) {
            if (enabled) GL11C.glEnable(capability);
            else GL11C.glDisable(capability);
        }


    }
}
