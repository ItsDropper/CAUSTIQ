package dev.itsdropper.caustiq.integration.nvidium;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import dev.itsdropper.caustiq.shaderpack.IrisShaderResources;

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

    /** Binds deterministic noise and neutral material maps to the explicit shader units 2-4. */
    public static void bindForTerrain() {
        bindForTerrain(0);
    }

    public static void bindForTerrain(int program) {
        if (bindingState != null || customResources != null) restoreBindings();
        ensureTextures();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] units = {
                GL13C.GL_TEXTURE2, GL13C.GL_TEXTURE5, GL13C.GL_TEXTURE6, GL13C.GL_TEXTURE7,
                GL13C.GL_TEXTURE12, GL13C.GL_TEXTURE13, GL13C.GL_TEXTURE14, GL13C.GL_TEXTURE15
        };
        int[] textures = {
                noiseTexture, NvidiumGBufferTargets.texture(1), NvidiumGBufferTargets.texture(2),
                NvidiumGBufferTargets.texture(4),
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture()),
                shadowOrFallback(NvidiumShadowRenderer.shadowTexture1()),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(0)),
                colorOrFallback(NvidiumShadowRenderer.shadowColorTexture(1))
        };
        int[] previousBindings = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            GL13C.glActiveTexture(units[i]);
            previousBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
        }
        GL13C.glActiveTexture(previousActive);
        bindingState = new BindingState(previousActive, units, previousBindings);
        if (program != 0) {
            int noiseLocation = GL20C.glGetUniformLocation(program, "noisetex");
            if (noiseLocation >= 0) GL20C.glUniform1i(noiseLocation, 2);
            int shadow0 = GL20C.glGetUniformLocation(program, "shadowtex0");
            if (shadow0 >= 0) GL20C.glUniform1i(shadow0, 12);
            int shadow1 = GL20C.glGetUniformLocation(program, "shadowtex1");
            if (shadow1 >= 0) GL20C.glUniform1i(shadow1, 13);
            int shadowColor0 = GL20C.glGetUniformLocation(program, "shadowcolor0");
            if (shadowColor0 >= 0) GL20C.glUniform1i(shadowColor0, 14);
            int shadowColor1 = GL20C.glGetUniformLocation(program, "shadowcolor1");
            if (shadowColor1 >= 0) GL20C.glUniform1i(shadowColor1, 15);
        }
    }

    /** Binds shadow depth samplers and deterministic noise for the Iris shadow terrain program. */
    public static void bindForShadow(int program) {
        if (bindingState != null || customResources != null) restoreBindings();
        ensureTextures();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] units = {GL13C.GL_TEXTURE2, GL13C.GL_TEXTURE12, GL13C.GL_TEXTURE13};
        int depth0 = shadowOrFallback(NvidiumShadowRenderer.shadowTexture());
        int depth1 = shadowOrFallback(NvidiumShadowRenderer.shadowTexture1());
        int[] textures = {noiseTexture, depth0, depth1};
        int[] previousBindings = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            GL13C.glActiveTexture(units[i]);
            previousBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
        }
        GL13C.glActiveTexture(previousActive);
        bindingState = new BindingState(previousActive, units, previousBindings);
        if (program != 0) {
            int noise = GL20C.glGetUniformLocation(program, "noisetex");
            if (noise >= 0) GL20C.glUniform1i(noise, 2);
            int shadow0 = GL20C.glGetUniformLocation(program, "shadowtex0");
            if (shadow0 >= 0) GL20C.glUniform1i(shadow0, 12);
            int shadow1 = GL20C.glGetUniformLocation(program, "shadowtex1");
            if (shadow1 >= 0) GL20C.glUniform1i(shadow1, 13);
        }
    }

    /** Returns the managed fallback noise texture for fullscreen sampler bindings. */
    public static int noiseTexture() {
        ensureTextures();
        return noiseTexture;
    }

    /** Restores CAUSTIQ's noise and Iris colortex units after the terrain draw. */
    public static void bindCustomTextures(IrisShaderResources resources, int program) {
        if (customResources != null) customResources.restoreTerrainTextures();
        customResources = resources;
        if (resources != null) resources.bindTerrainTextures(program, 8);
    }

    public static void restoreBindings() {
        BindingState state = bindingState;
        bindingState = null;
        if (state != null) {
            for (int i = 0; i < state.units().length; i++) {
                GL13C.glActiveTexture(state.units()[i]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.bindings()[i]);
            }
            GL13C.glActiveTexture(state.activeTexture());
        }
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
