package dev.itsdropper.caustiq.integration.nvidium;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.util.Random;

/** Owns the fallback Iris textures used by the translated Photon terrain shader. */
public final class NvidiumIrisTextureBridge {
    private static final int NOISE_SIZE = 512;
    private static int noiseTexture;
    private static int normalTexture;
    private static int specularTexture;
    private static BindingState bindingState;

    private NvidiumIrisTextureBridge() { }

    /** Binds deterministic noise and neutral material maps to the explicit shader units 2-4. */
    public static void bindForTerrain() {
        if (bindingState != null) restoreBindings();
        ensureTextures();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] previousBindings = new int[3];
        int[] units = {GL13C.GL_TEXTURE2, GL13C.GL_TEXTURE3, GL13C.GL_TEXTURE4,
                GL13C.GL_TEXTURE5, GL13C.GL_TEXTURE6, GL13C.GL_TEXTURE7, GL13C.GL_TEXTURE8,
                GL13C.GL_TEXTURE10, GL13C.GL_TEXTURE11, GL13C.GL_TEXTURE12, GL13C.GL_TEXTURE13,
                GL13C.GL_TEXTURE15};
        int[] textures = {noiseTexture, normalTexture, specularTexture,
                noiseTexture, noiseTexture, noiseTexture, noiseTexture,
                noiseTexture, noiseTexture, noiseTexture, noiseTexture, noiseTexture};
        for (int i = 0; i < units.length; i++) {
            GL13C.glActiveTexture(units[i]);
            previousBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
        }
        GL13C.glActiveTexture(previousActive);
        bindingState = new BindingState(previousActive, units, previousBindings);
    }

    /** Restores texture units 2-4 exactly as they were before the Nvidium terrain draw. */
    public static void restoreBindings() {
        BindingState state = bindingState;
        if (state == null) return;
        bindingState = null;
        for (int i = 0; i < state.units().length; i++) {
            GL13C.glActiveTexture(state.units()[i]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.bindings()[i]);
        }
        GL13C.glActiveTexture(state.activeTexture());
    }

    /** Releases owned textures; call on the render thread while an OpenGL context is current. */
    public static void destroy() {
        restoreBindings();
        if (noiseTexture != 0) GL11C.glDeleteTextures(noiseTexture);
        if (normalTexture != 0) GL11C.glDeleteTextures(normalTexture);
        if (specularTexture != 0) GL11C.glDeleteTextures(specularTexture);
        noiseTexture = 0;
        normalTexture = 0;
        specularTexture = 0;
    }

    private static void ensureTextures() {
        if (noiseTexture == 0) noiseTexture = createNoiseTexture();
        if (normalTexture == 0) normalTexture = createSolidTexture(128, 128, 255, 255);
        if (specularTexture == 0) specularTexture = createSolidTexture(0, 0, 0, 255);
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

    private static int createSolidTexture(int red, int green, int blue, int alpha) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha).flip();
        return createTexture(1, 1, pixel);
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
