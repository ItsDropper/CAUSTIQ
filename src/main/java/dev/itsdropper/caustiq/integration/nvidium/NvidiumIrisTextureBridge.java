package dev.itsdropper.caustiq.integration.nvidium;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;

import java.nio.ByteBuffer;
import java.util.Random;

/** Owns the fallback Iris textures used by the translated Photon terrain shader. */
public final class NvidiumIrisTextureBridge {
    private static final int NOISE_SIZE = 512;
    private static int noiseTexture;
    private static int fallbackDepthTexture;
    private static int fallbackColorTexture;
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
        int[] units = {GL13C.GL_TEXTURE2, GL13C.GL_TEXTURE12, GL13C.GL_TEXTURE13,
                GL13C.GL_TEXTURE14, GL13C.GL_TEXTURE15};
        int[] textures = {noiseTexture,
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture()),
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture1()),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(0)),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(1))};
        bindingState = bindTextures(previousActive, units, textures);
        setSampler(program, "noisetex", 2);
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
        int[] units = {GL13C.GL_TEXTURE2, GL13C.GL_TEXTURE12, GL13C.GL_TEXTURE13,
                GL13C.GL_TEXTURE14, GL13C.GL_TEXTURE15};
        int[] textures = {noiseTexture,
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture()),
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture1()),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(0)),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(1))};
        bindingState = bindTextures(previousActive, units, textures);
        setSampler(program, "noisetex", 2);
        setSampler(program, "shadowtex0", 12);
        setSampler(program, "shadowtex1", 13);
        setSampler(program, "shadowcolor0", 14);
        setSampler(program, "shadowcolor1", 15);
        return bindColortexSamplers(program, previousActive);
    }

    private static int bindColortexSamplers(int program, int previousActive) {
        if (program == 0) return 5;
        int unit = 5;
        int maximum = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        java.util.ArrayList<Integer> units = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> previousBindings = new java.util.ArrayList<>();
        try {
            for (int attachment = 1; attachment <= 8; attachment++) {
                int location = GL20C.glGetUniformLocation(program, "caustiqColortex" + attachment);
                if (location < 0) continue;
                if (unit >= Math.min(maximum, 12)) {
                    throw new IllegalStateException("Iris colortex samplers exceed the texture units reserved before shadow samplers.");
                }
                int textureUnit = GL13C.GL_TEXTURE0 + unit;
                GL13C.glActiveTexture(textureUnit);
                units.add(textureUnit);
                previousBindings.add(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D));
                int texture = attachment <= NvidiumGBufferTargets.MAX_ATTACHMENT
                        ? NvidiumGBufferTargets.texture(attachment)
                        : ShaderRuntime.irisColorTexture(attachment);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorOrFallback(texture));
                GL20C.glUniform1i(location, unit);
                unit++;
            }
        } catch (RuntimeException exception) {
            restoreState(new BindingState(previousActive,
                    units.stream().mapToInt(Integer::intValue).toArray(),
                    previousBindings.stream().mapToInt(Integer::intValue).toArray()));
            restoreBindings();
            throw exception;
        }
        if (!units.isEmpty()) {
            bindingState = merge(bindingState, new BindingState(previousActive,
                    units.stream().mapToInt(Integer::intValue).toArray(),
                    previousBindings.stream().mapToInt(Integer::intValue).toArray()));
        }
        GL13C.glActiveTexture(previousActive);
        return unit;
    }

    private static BindingState bindTextures(int previousActive, int[] units, int[] textures) {
        int[] previousBindings = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            GL13C.glActiveTexture(units[i]);
            previousBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
        }
        GL13C.glActiveTexture(previousActive);
        return new BindingState(previousActive, units, previousBindings);
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
        System.arraycopy(second.units(), 0, units, first.units().length, second.units().length);
        System.arraycopy(second.bindings(), 0, bindings, first.bindings().length, second.bindings().length);
        return new BindingState(first.activeTexture(), units, bindings);
    }

    private static void restoreState(BindingState state) {
        for (int i = 0; i < state.units().length; i++) {
            GL13C.glActiveTexture(state.units()[i]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.bindings()[i]);
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
        if (resources != null) resources.bindTerrainTextures(program, firstTextureUnit, 12);
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
        if (fallbackColorTexture != 0) GL11C.glDeleteTextures(fallbackColorTexture);
        noiseTexture = 0;
        fallbackDepthTexture = 0;
        fallbackColorTexture = 0;
    }

    private static void ensureTextures() {
        if (noiseTexture == 0) noiseTexture = createNoiseTexture();
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

    private record BindingState(int activeTexture, int[] units, int[] bindings) {
        private BindingState {
            units = units.clone();
            bindings = bindings.clone();
        }
    }
}
