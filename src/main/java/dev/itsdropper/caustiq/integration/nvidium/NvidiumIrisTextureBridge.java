package dev.itsdropper.caustiq.integration.nvidium;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;

import java.nio.ByteBuffer;
import java.util.Random;

/** Owns the fallback Iris textures used by the translated Photon terrain shader. */
public final class NvidiumIrisTextureBridge {
    public static final int MAX_COLORTEX_SAMPLER = 15;
    private static final int NOISE_SIZE = 512;
    private static int noiseTexture;
    private static int fallbackDepthTexture;
    private static int depthSnapshotTexture;
    private static int depthSnapshotFramebuffer;
    private static int depthSnapshotFrame = Integer.MIN_VALUE;
    private static int depthSnapshotWidth;
    private static int depthSnapshotHeight;
    private static int depthSnapshotInternalFormat;
    private static int fallbackColorTexture;
    private static int shadowComparisonSampler;
    private static BindingState bindingState;
    private static IrisShaderResources customResources;

    private NvidiumIrisTextureBridge() { }

    /** Binds shared noise/shadow textures and every active Iris colortex sampler. */
    public static void bindForTerrain() {
        bindForTerrain(0);
    }

    public static int bindForTerrain(int program) {
        if (bindingState != null || customResources != null) restoreBindings();
        ensureTextures();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        boolean needsDepthSnapshot = program != 0 && GL20C.glGetUniformLocation(program, "caustiqDepthTexture1") >= 0;
        boolean needsNormals = program != 0 && GL20C.glGetUniformLocation(program, "caustiqNormalTexture") >= 0;
        boolean needsSpecular = program != 0 && GL20C.glGetUniformLocation(program, "caustiqSpecularTexture") >= 0;
        java.util.ArrayList<Integer> units = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> textures = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> samplers = new java.util.ArrayList<>();
        units.add(GL13C.GL_TEXTURE2); textures.add(noiseTexture); samplers.add(0);
        if (needsDepthSnapshot) { units.add(GL13C.GL_TEXTURE3); textures.add(captureDepthSnapshot()); samplers.add(0); }
        if (needsNormals) { units.add(GL13C.GL_TEXTURE4); textures.add(NvidiumPbrTextureAtlases.normalsTexture()); samplers.add(0); }
        if (needsSpecular) { units.add(GL13C.GL_TEXTURE5); textures.add(NvidiumPbrTextureAtlases.specularTexture()); samplers.add(0); }
        units.add(GL13C.GL_TEXTURE12); textures.add(shadowOrFallback(NvidiumShadowRenderer.shadowTexture())); samplers.add(0);
        units.add(GL13C.GL_TEXTURE13); textures.add(shadowOrFallback(NvidiumShadowRenderer.shadowTexture1())); samplers.add(shadowComparisonSampler);
        units.add(GL13C.GL_TEXTURE14); textures.add(colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(0))); samplers.add(0);
        units.add(GL13C.GL_TEXTURE15); textures.add(colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(1))); samplers.add(0);
        bindingState = bindTextures(previousActive, units.stream().mapToInt(Integer::intValue).toArray(),
                textures.stream().mapToInt(Integer::intValue).toArray(), samplers.stream().mapToInt(Integer::intValue).toArray());
        setSampler(program, "noisetex", 2);
        if (needsDepthSnapshot) setSampler(program, "caustiqDepthTexture1", 3);
        if (needsNormals) setSampler(program, "caustiqNormalTexture", 4);
        if (needsSpecular) setSampler(program, "caustiqSpecularTexture", 5);
        setSampler(program, "shadowtex0", 12);
        setSampler(program, "shadowtex1", 13);
        setSampler(program, "shadowcolor0", 14);
        setSampler(program, "shadowcolor1", 15);
        return bindColortexSamplers(program, previousActive);
    }

    /** Binds shadow resources and any Iris colortex samplers referenced by the shadow program. */
    public static int bindForShadow(int program) {
        if (bindingState != null || customResources != null) restoreBindings();
        ensureTextures();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        java.util.ArrayList<Integer> units = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> textures = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> samplers = new java.util.ArrayList<>();
        units.add(GL13C.GL_TEXTURE2); textures.add(noiseTexture); samplers.add(0);
        if (program != 0 && GL20C.glGetUniformLocation(program, "caustiqNormalTexture") >= 0) {
            units.add(GL13C.GL_TEXTURE4); textures.add(NvidiumPbrTextureAtlases.normalsTexture()); samplers.add(0);
            setSampler(program, "caustiqNormalTexture", 4);
        }
        if (program != 0 && GL20C.glGetUniformLocation(program, "caustiqSpecularTexture") >= 0) {
            units.add(GL13C.GL_TEXTURE5); textures.add(NvidiumPbrTextureAtlases.specularTexture()); samplers.add(0);
            setSampler(program, "caustiqSpecularTexture", 5);
        }
        units.add(GL13C.GL_TEXTURE12); textures.add(shadowOrFallback(NvidiumShadowRenderer.shadowTexture())); samplers.add(0);
        units.add(GL13C.GL_TEXTURE13); textures.add(shadowOrFallback(NvidiumShadowRenderer.shadowTexture1())); samplers.add(shadowComparisonSampler);
        units.add(GL13C.GL_TEXTURE14); textures.add(colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(0))); samplers.add(0);
        units.add(GL13C.GL_TEXTURE15); textures.add(colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(1))); samplers.add(0);
        bindingState = bindTextures(previousActive, units.stream().mapToInt(Integer::intValue).toArray(),
                textures.stream().mapToInt(Integer::intValue).toArray(), samplers.stream().mapToInt(Integer::intValue).toArray());
        setSampler(program, "noisetex", 2);
        setSampler(program, "shadowtex0", 12);
        setSampler(program, "shadowtex1", 13);
        setSampler(program, "shadowcolor0", 14);
        setSampler(program, "shadowcolor1", 15);
        return bindColortexSamplers(program, previousActive);
    }

    private static int bindColortexSamplers(int program, int previousActive) {
        if (program == 0) return 6;
        int unit = 6;
        int maximum = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        java.util.ArrayList<Integer> units = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> previousBindings = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> previousSamplers = new java.util.ArrayList<>();
        try {
            for (int attachment = 1; attachment <= MAX_COLORTEX_SAMPLER; attachment++) {
                int location = GL20C.glGetUniformLocation(program, "caustiqColortex" + attachment);
                if (location < 0) continue;
                unit = firstCustomTextureUnit(unit);
                if (unit >= maximum) {
                    throw new IllegalStateException("Iris colortex samplers exceed the fragment texture-unit limit after reserving PBR and shadow textures.");
                }
                int textureUnit = GL13C.GL_TEXTURE0 + unit;
                GL13C.glActiveTexture(textureUnit);
                units.add(textureUnit);
                previousBindings.add(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D));
                previousSamplers.add(GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit));
                GL33C.glBindSampler(unit, 0);
                int texture = ShaderRuntime.irisColorTexture(attachment);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorOrFallback(texture));
                GL20C.glUniform1i(location, unit);
                unit++;
            }
        } catch (RuntimeException exception) {
            restoreState(new BindingState(previousActive,
                    units.stream().mapToInt(Integer::intValue).toArray(),
                    previousBindings.stream().mapToInt(Integer::intValue).toArray(),
                    previousSamplers.stream().mapToInt(Integer::intValue).toArray()));
            restoreBindings();
            throw exception;
        }
        if (!units.isEmpty()) {
            bindingState = merge(bindingState, new BindingState(previousActive,
                    units.stream().mapToInt(Integer::intValue).toArray(),
                    previousBindings.stream().mapToInt(Integer::intValue).toArray(),
                    previousSamplers.stream().mapToInt(Integer::intValue).toArray()));
        }
        unit = firstCustomTextureUnit(unit);
        GL13C.glActiveTexture(previousActive);
        return unit;
    }

    static int firstCustomTextureUnit(int nextUnit) {
        return IrisShaderResources.nextAvailableTerrainTextureUnit(nextUnit);
    }

    private static BindingState bindTextures(int previousActive, int[] units, int[] textures, int[] samplers) {
        int[] previousBindings = new int[units.length];
        int[] previousSamplers = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            int unit = units[i] - GL13C.GL_TEXTURE0;
            GL13C.glActiveTexture(units[i]);
            previousBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            previousSamplers[i] = GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            GL33C.glBindSampler(unit, samplers[i]);
        }
        GL13C.glActiveTexture(previousActive);
        return new BindingState(previousActive, units, previousBindings, previousSamplers);
    }

    private static void setSampler(int program, String name, int unit) {
        if (program == 0) return;
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1i(location, unit);
    }

    private static BindingState merge(BindingState first, BindingState second) {
        if (first == null) return second;
        int[] units = java.util.Arrays.copyOf(first.units(), first.units().length + second.units().length);
        int[] bindings = java.util.Arrays.copyOf(first.bindings(), first.bindings().length + second.bindings().length);
        int[] samplers = java.util.Arrays.copyOf(first.samplers(), first.samplers().length + second.samplers().length);
        System.arraycopy(second.units(), 0, units, first.units().length, second.units().length);
        System.arraycopy(second.bindings(), 0, bindings, first.bindings().length, second.bindings().length);
        System.arraycopy(second.samplers(), 0, samplers, first.samplers().length, second.samplers().length);
        return new BindingState(first.activeTexture(), units, bindings, samplers);
    }

    private static void restoreState(BindingState state) {
        for (int i = 0; i < state.units().length; i++) {
            GL13C.glActiveTexture(state.units()[i]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.bindings()[i]);
            GL33C.glBindSampler(state.units()[i] - GL13C.GL_TEXTURE0, state.samplers()[i]);
        }
        GL13C.glActiveTexture(state.activeTexture());
    }

    /** Returns the managed fallback noise texture for fullscreen sampler bindings. */
    public static int noiseTexture() {
        ensureTextures();
        return noiseTexture;
    }

    /** Restores CAUSTIQ's noise and Iris colortex units after the terrain draw. */
    public static void bindCustomTextures(IrisShaderResources resources, int program) {
        bindCustomTextures(resources, program, 5);
    }

    public static void bindCustomTextures(IrisShaderResources resources, int program, int firstTextureUnit) {
        if (customResources != null) customResources.restoreTerrainTextures();
        customResources = resources;
        if (resources != null) resources.bindTerrainTextures(
                program, firstTextureUnit, GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS));
    }

    public static void restoreBindings() {
        BindingState state = bindingState;
        bindingState = null;
        if (state != null) restoreState(state);
        if (customResources != null) {
            customResources.restoreTerrainTextures();
            customResources = null;
        }
    }

    /** Releases owned textures; call on the render thread while an OpenGL context is current. */
    public static void destroy() {
        restoreBindings();
        if (noiseTexture != 0) GL11C.glDeleteTextures(noiseTexture);
        if (fallbackDepthTexture != 0) GL11C.glDeleteTextures(fallbackDepthTexture);
        if (depthSnapshotTexture != 0) GL11C.glDeleteTextures(depthSnapshotTexture);
        if (depthSnapshotFramebuffer != 0) GL30C.glDeleteFramebuffers(depthSnapshotFramebuffer);
        if (fallbackColorTexture != 0) GL11C.glDeleteTextures(fallbackColorTexture);
        if (shadowComparisonSampler != 0) GL33C.glDeleteSamplers(shadowComparisonSampler);
        NvidiumPbrTextureAtlases.destroy();
        noiseTexture = 0;
        fallbackDepthTexture = 0;
        depthSnapshotTexture = 0;
        depthSnapshotFramebuffer = 0;
        depthSnapshotFrame = Integer.MIN_VALUE;
        depthSnapshotWidth = 0;
        depthSnapshotHeight = 0;
        depthSnapshotInternalFormat = 0;
        fallbackColorTexture = 0;
        shadowComparisonSampler = 0;
    }

    private static void ensureTextures() {
        if (noiseTexture == 0) noiseTexture = createNoiseTexture();
        if (shadowComparisonSampler == 0) shadowComparisonSampler = createShadowComparisonSampler();
        if (fallbackDepthTexture == 0) fallbackDepthTexture = createFallbackDepthTexture();
        if (fallbackColorTexture == 0) {
            ByteBuffer white = ByteBuffer.allocateDirect(4);
            white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
            fallbackColorTexture = createTexture(1, 1, white);
        }
    }

    private static int shadowOrFallback(int texture) {
        return texture == 0 ? fallbackDepthTexture : texture;
    }

    private static int colorOrFallback(int texture) {
        return texture == 0 ? fallbackColorTexture : texture;
    }

    /** Captures opaque scene depth after Nvidium's solid pass and before translucent terrain. */
    public static void captureOpaqueDepthSnapshot() {
        captureDepthSnapshot();
    }

    /** Returns opaque depth only while the snapshot belongs to the active Nvidium frame. */
    public static int depthTexture1() {
        int frame = NvidiumIrisUniformBridge.currentFrameCounter();
        return isDepthSnapshotCurrent(depthSnapshotFrame, frame) ? depthSnapshotTexture : 0;
    }

    static boolean isDepthSnapshotCurrent(int snapshotFrame, int currentFrame) {
        return currentFrame > 0 && snapshotFrame == currentFrame;
    }

    /**
     * Copies the currently bound scene depth buffer before Nvidium's translucent
     * terrain pass. Iris depthtex1 represents opaque depth, so sampling the live
     * framebuffer attachment while translucency is being drawn would be a
     * framebuffer feedback loop.
     */
    private static int captureDepthSnapshot() {
        int[] viewport = new int[4];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer values = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, values);
            for (int i = 0; i < 4; i++) viewport[i] = values.get(i);
        }
        int width = viewport[2];
        int height = viewport[3];
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Cannot capture Iris depthtex1 without a non-empty Nvidium viewport.");
        }

        // Invalidate first: a failed copy must never leave a prior snapshot looking current.
        depthSnapshotFrame = Integer.MIN_VALUE;
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int sourceFramebuffer = previousDrawFramebuffer;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            int sourceDepthFormat = depthAttachmentInternalFormat(sourceFramebuffer);
            if (depthSnapshotTexture == 0) depthSnapshotTexture = GL11C.glGenTextures();
            if (depthSnapshotFramebuffer == 0) depthSnapshotFramebuffer = GL30C.glGenFramebuffers();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthSnapshotTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 0x812F);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 0x812F);
            if (depthSnapshotWidth != width || depthSnapshotHeight != height
                    || depthSnapshotInternalFormat != sourceDepthFormat) {
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, sourceDepthFormat,
                        width, height, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT,
                        (ByteBuffer) null);
                depthSnapshotWidth = width;
                depthSnapshotHeight = height;
                depthSnapshotInternalFormat = sourceDepthFormat;
            }

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, depthSnapshotFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, depthSnapshotTexture, 0);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Opaque-depth snapshot framebuffer is incomplete (0x"
                        + Integer.toHexString(status) + ").");
            }
            blitDepthSnapshot(sourceFramebuffer, depthSnapshotFramebuffer, viewport);
            depthSnapshotFrame = NvidiumIrisUniformBridge.currentFrameCounter();
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL13C.glActiveTexture(previousActive);
        }
        return depthSnapshotTexture;
    }

    /**
     * Reads the exact internal format of the source depth attachment. OpenGL depth blits
     * require identical source and destination depth formats; matching only bit depth is
     * insufficient (for example, DEPTH_COMPONENT24 and DEPTH_COMPONENT32F are incompatible).
     */
    static int depthAttachmentInternalFormat(int framebuffer) {
        int objectType = GL30C.glGetFramebufferAttachmentParameteri(
                GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int objectName = GL30C.glGetFramebufferAttachmentParameteri(
                GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (objectType == GL11C.GL_TEXTURE) {
            int level = GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            int cubeFace = GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
            int target = cubeFace == 0 ? GL11C.GL_TEXTURE_2D : GL13C.GL_TEXTURE_CUBE_MAP;
            int bindingParameter = cubeFace == 0
                    ? GL11C.GL_TEXTURE_BINDING_2D : GL13C.GL_TEXTURE_BINDING_CUBE_MAP;
            int previousTexture = GL11C.glGetInteger(bindingParameter);
            try {
                GL11C.glBindTexture(target, objectName);
                return GL11C.glGetTexLevelParameteri(target, level, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            } finally {
                GL11C.glBindTexture(target, previousTexture);
            }
        }
        if (objectType == GL30C.GL_RENDERBUFFER) {
            int previousRenderbuffer = GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
            try {
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, objectName);
                return GL30C.glGetRenderbufferParameteri(
                        GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_INTERNAL_FORMAT);
            } finally {
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, previousRenderbuffer);
            }
        }
        throw new IllegalStateException("Nvidium's source framebuffer " + framebuffer
                + " has no queryable depth attachment (object type " + objectType + ").");
    }

    /**
     * Copies the full depth region and restores the caller's framebuffer/scissor state.
     * Framebuffer blits obey scissor testing, so leaving it enabled can produce a partial,
     * mixed-frame snapshot when the caller has a smaller scissor rectangle active.
     */
    static void blitDepthSnapshot(int sourceFramebuffer, int destinationFramebuffer, int[] viewport) {
        if (viewport == null || viewport.length < 4 || viewport[2] <= 0 || viewport[3] <= 0) {
            throw new IllegalArgumentException("Depth snapshot viewport must contain a positive width and height.");
        }
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissorWasEnabled = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
            if (scissorWasEnabled) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBlitFramebuffer(
                    viewport[0], viewport[1], viewport[0] + viewport[2], viewport[1] + viewport[3],
                    0, 0, viewport[2], viewport[3], GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        } finally {
            if (scissorWasEnabled) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        }
    }

    private static int createShadowComparisonSampler() {
        int sampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, 0x812F);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, 0x812F);
        GL33C.glSamplerParameteri(sampler, 0x884C, 0x884E);
        GL33C.glSamplerParameteri(sampler, 0x884D, GL11C.GL_LEQUAL);
        return sampler;
    }

    private static int createFallbackDepthTexture() {
        int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        ByteBuffer depth = ByteBuffer.allocateDirect(Float.BYTES).order(java.nio.ByteOrder.nativeOrder());
        depth.putFloat(1.0f).flip();
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 0x812F);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 0x812F);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F,
                1, 1, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousBinding);
        GL13C.glActiveTexture(activeTexture);
        return texture;
    }

    private static int createNoiseTexture() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(NOISE_SIZE * NOISE_SIZE * 4);
        Random random = new Random(0xCA0571C);
        for (int i = 0; i < NOISE_SIZE * NOISE_SIZE; i++) {
            byte value = (byte) random.nextInt(256);
            pixels.put(value).put(value).put(value).put((byte) 255);
        }
        pixels.flip();
        return createTexture(NOISE_SIZE, NOISE_SIZE, pixels);
    }

    private static int createTexture(int width, int height, ByteBuffer pixels) {
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8,
                width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousBinding);
        GL13C.glActiveTexture(previousActive);
        return texture;
    }

    private record BindingState(int activeTexture, int[] units, int[] bindings, int[] samplers) {
        private BindingState {
            units = units.clone();
            bindings = bindings.clone();
            samplers = samplers.clone();
        }
    }
}
