package dev.itsdropper.caustiq.integration.nvidium;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.itsdropper.caustiq.CaustiqClient;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Temporarily adds CAUSTIQ's auxiliary terrain G-buffer attachments to the
 * framebuffer Nvidium is currently rasterizing into. Existing color/depth
 * attachments and draw-buffer routing are restored immediately after the draw.
 *
 * <p>Attachment 0 remains Sodium/Nvidium's normal shaded terrain color.
 * Attachments 1-4 contain geometric normal, camera-relative render position,
 * packed vertex tint/material flags, and normalized lightmap coordinates.</p>
 */
public final class NvidiumGBufferTargets {
    public static final int NORMAL = 1;
    public static final int POSITION = 2;
    public static final int MATERIAL = 3;
    public static final int LIGHTMAP = 4;
    private static final int FIRST_ATTACHMENT = GL30C.GL_COLOR_ATTACHMENT1;
    private static final int ATTACHMENT_COUNT = 4;
    private static final int REQUIRED_DRAW_BUFFERS = ATTACHMENT_COUNT + 1;

    private static final int[] TEXTURES = new int[ATTACHMENT_COUNT];
    private static final TextureTarget[] TARGETS = new TextureTarget[ATTACHMENT_COUNT];
    private static int width;
    private static int height;
    private static FramebufferState activeState;
    private static boolean warnedUnavailable;

    private NvidiumGBufferTargets() { }

    /** Called at the Nvidium primary terrain rasterizer boundary, on the render thread. */
    public static boolean begin() {
        if (activeState != null) end();
        int framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) return false;

        int drawBufferCount = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
        if (drawBufferCount < REQUIRED_DRAW_BUFFERS) {
            warnUnavailable("OpenGL exposes only " + drawBufferCount
                    + " draw buffers; CAUSTIQ needs " + REQUIRED_DRAW_BUFFERS + ".");
            return false;
        }

        int viewportWidth;
        int viewportHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            viewportWidth = viewport.get(2);
            viewportHeight = viewport.get(3);
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) return false;

        ensureTargets(viewportWidth, viewportHeight);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        int[] previousDrawBuffers = new int[drawBufferCount];
        for (int i = 0; i < drawBufferCount; i++) {
            previousDrawBuffers[i] = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0 + i);
        }

        AttachmentState[] previousAttachments = new AttachmentState[ATTACHMENT_COUNT];
        for (int i = 0; i < ATTACHMENT_COUNT; i++) {
            int attachment = FIRST_ATTACHMENT + i;
            int type = GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int object = type == GL11C.GL_NONE ? 0 : GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int level = type == GL11C.GL_TEXTURE ? GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) : 0;
            int cubeFace = type == GL11C.GL_TEXTURE ? GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) : 0;
            previousAttachments[i] = new AttachmentState(type, object, level, cubeFace);
        }

        FramebufferState state = new FramebufferState(framebuffer, previousDrawBuffers, previousAttachments);
        for (int i = 0; i < ATTACHMENT_COUNT; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, FIRST_ATTACHMENT + i,
                    GL11C.GL_TEXTURE_2D, TEXTURES[i], 0);
        }
        int[] drawBuffers = new int[REQUIRED_DRAW_BUFFERS];
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        GL20C.glDrawBuffers(drawBuffers);

        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            restore(state);
            warnUnavailable("Nvidium's active framebuffer cannot accept CAUSTIQ G-buffer attachments (status 0x"
                    + Integer.toHexString(status) + ").");
            return false;
        }
        clearAuxiliaryAttachments();

        activeState = state;
        warnedUnavailable = false;
        return true;
    }

    /** Restores the exact draw-buffer routing and attachments captured by begin(). */
    public static void end() {
        FramebufferState state = activeState;
        if (state == null) return;
        activeState = null;
        int currentFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (currentFramebuffer != state.framebuffer()) {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.framebuffer());
            restore(state);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, currentFramebuffer);
            return;
        }
        restore(state);
    }

    /** Returns the GL texture object for an auxiliary color attachment (1 through 4). */
    public static int texture(int attachment) {
        if (attachment < NORMAL || attachment > LIGHTMAP) {
            throw new IllegalArgumentException("G-buffer attachment must be between 1 and 4.");
        }
        return TEXTURES[attachment - 1];
    }

    /** Minecraft GPU view for sampling this attachment in a RenderPass. */
    public static GpuTextureView view(int attachment) {
        if (attachment < NORMAL || attachment > LIGHTMAP) {
            throw new IllegalArgumentException("G-buffer attachment must be between 1 and 4.");
        }
        TextureTarget target = TARGETS[attachment - 1];
        return target == null ? null : target.getColorTextureView();
    }

    public static int width() { return width; }
    public static int height() { return height; }
    public static boolean isAttachedForCurrentDraw() { return activeState != null; }

    /** Releases persistent G-buffer textures; call on the render thread. */
    public static void destroy() {
        end();
        for (int i = 0; i < TARGETS.length; i++) {
            if (TARGETS[i] != null) TARGETS[i].destroyBuffers();
            TARGETS[i] = null;
            TEXTURES[i] = 0;
        }
        width = 0;
        height = 0;
    }

    private static void ensureTargets(int newWidth, int newHeight) {
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        if (TARGETS[0] == null) {
            for (int i = 0; i < TARGETS.length; i++) {
                TARGETS[i] = new TextureTarget("Caustiq Nvidium G-buffer " + (i + 1),
                        newWidth, newHeight, false);
            }
        } else if (width != newWidth || height != newHeight) {
            for (TextureTarget target : TARGETS) target.resize(newWidth, newHeight);
        }
        int previousActiveTexture = GL11C.glGetInteger(GL13_ACTIVE_TEXTURE);
        int previousTextureBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            for (int i = 0; i < TARGETS.length; i++) {
                TEXTURES[i] = ((GlTexture) TARGETS[i].getColorTexture()).glId();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, TEXTURES[i]);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
            }
            width = newWidth;
            height = newHeight;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTextureBinding);
            GL13C.glActiveTexture(previousActiveTexture);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    private static void clearAuxiliaryAttachments() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < ATTACHMENT_COUNT; i++) {
                FloatBuffer clearValue = stack.mallocFloat(4);
                if (i == 0) {
                    clearValue.put(0, 0.5f).put(1, 0.5f).put(2, 1.0f).put(3, 0.0f);
                } else {
                    clearValue.put(0, 0).put(1, 0).put(2, 0).put(3, 0);
                }
                GL30C.glClearBufferfv(GL11C.GL_COLOR, i + 1, clearValue);
            }
        }
    }

    private static void restore(FramebufferState state) {
        for (int i = 0; i < ATTACHMENT_COUNT; i++) {
            int attachment = FIRST_ATTACHMENT + i;
            AttachmentState previous = state.attachments()[i];
            if (previous.type() == GL11C.GL_TEXTURE) {
                int target = previous.cubeFace() == 0 ? GL11C.GL_TEXTURE_2D : previous.cubeFace();
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                        target, previous.object(), previous.level());
            } else if (previous.type() == GL30C.GL_RENDERBUFFER) {
                GL30C.glFramebufferRenderbuffer(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30C.GL_RENDERBUFFER, previous.object());
            } else {
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                        GL11C.GL_TEXTURE_2D, 0, 0);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30C.GL_RENDERBUFFER, 0);
            }
        }
        GL20C.glDrawBuffers(state.drawBuffers());
    }

    private static void warnUnavailable(String reason) {
        if (warnedUnavailable) return;
        warnedUnavailable = true;
        CaustiqClient.LOGGER.warn("{} Auxiliary terrain buffers are disabled for this draw.", reason);
    }

    private static final int GL13_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL12_CLAMP_TO_EDGE = 0x812F;

    private record AttachmentState(int type, int object, int level, int cubeFace) { }
    private record FramebufferState(int framebuffer, int[] drawBuffers, AttachmentState[] attachments) {
        private FramebufferState {
            drawBuffers = Arrays.copyOf(drawBuffers, drawBuffers.length);
            attachments = Arrays.copyOf(attachments, attachments.length);
        }
    }
}
