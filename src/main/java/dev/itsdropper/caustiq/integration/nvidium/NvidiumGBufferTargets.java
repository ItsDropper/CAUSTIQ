package dev.itsdropper.caustiq.integration.nvidium;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.shaderpack.IrisTerrainPassScheduler;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Map;

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
    public static final int MAX_ATTACHMENT = 7;
    private static final int FIRST_ATTACHMENT = GL30C.GL_COLOR_ATTACHMENT1;
    private static final int ATTACHMENT_COUNT = MAX_ATTACHMENT;
    private static final int REQUIRED_DRAW_BUFFERS = ATTACHMENT_COUNT + 1;

    private static final int[] TEXTURES = new int[ATTACHMENT_COUNT];
    private static final int[] ALTERNATE_TEXTURES = new int[ATTACHMENT_COUNT];
    private static final TextureTarget[] TARGETS = new TextureTarget[ATTACHMENT_COUNT];
    private static final TextureTarget[] ALTERNATE_TARGETS = new TextureTarget[ATTACHMENT_COUNT];
    private static int width;
    private static int height;
    private static FramebufferState activeState;
    private static boolean warnedUnavailable;
    private static Map<Integer, IrisTerrainPassScheduler.RenderTarget> targetSpecs = Map.of();
    private static boolean formatsDirty = true;
    private static int[] outputRouting = {0, 1, 2, 3, 4, 5, 6, 7};

    private NvidiumGBufferTargets() { }

    /** Applies Iris colortex format/filter/clear directives before the next terrain draw. */
    public static void configureTargets(Map<Integer, IrisTerrainPassScheduler.RenderTarget> specs) {
        targetSpecs = Map.copyOf(specs);
        formatsDirty = true;
    }

    /** Maps fragment output locations to Iris colortex indices for the active terrain program. */
    public static void configureOutputRouting(java.util.List<Integer> targetIndices) {
        outputRouting = createOutputRouting(targetIndices);
    }

    static int[] createOutputRouting(java.util.List<Integer> targetIndices) {
        if (targetIndices.size() > MAX_ATTACHMENT) {
            throw new IllegalArgumentException("Nvidium reserves one draw-buffer location for native color; at most seven Iris MRT outputs are supported.");
        }
        int[] routing = new int[REQUIRED_DRAW_BUFFERS];
        Arrays.fill(routing, -1);
        routing[0] = 0;
        for (int irisIndex = 0; irisIndex < targetIndices.size(); irisIndex++) {
            int target = targetIndices.get(irisIndex);
            if (target < 1 || target > MAX_ATTACHMENT) {
                throw new IllegalArgumentException("Nvidium reserves colortex0 and supports Iris colortex1 through colortex7.");
            }
            routing[irisIndex + 1] = target;
        }
        return routing;
    }

    /** Restores Nvidium's native location-to-attachment mapping. */
    public static void resetOutputRouting() {
        outputRouting = new int[]{0, 1, 2, 3, 4, 5, 6, 7};
    }

    /** Called at the Nvidium primary terrain rasterizer boundary, on the render thread. */
    public static boolean begin(boolean clearAttachments) {
        if (activeState != null) end();
        int framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) return false;

        int drawBufferCount = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
        int colorAttachmentCount = GL11C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS);
        if (drawBufferCount < REQUIRED_DRAW_BUFFERS || colorAttachmentCount < REQUIRED_DRAW_BUFFERS) {
            warnUnavailable("OpenGL exposes " + drawBufferCount + " draw buffers and "
                    + colorAttachmentCount + " color attachments; CAUSTIQ needs at least "
                    + REQUIRED_DRAW_BUFFERS + " of each.");
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
        Arrays.fill(drawBuffers, GL11C.GL_NONE);
        for (int location = 0; location < outputRouting.length; location++) {
            int target = outputRouting[location];
            if (target >= 0) drawBuffers[location] = GL30C.GL_COLOR_ATTACHMENT0 + target;
        }

        int[] identityDrawBuffers = new int[REQUIRED_DRAW_BUFFERS];
        for (int i = 0; i < identityDrawBuffers.length; i++) {
            identityDrawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        }
        try {
            // Completeness and clears use identity routing so every colortex attachment can be cleared.
            GL20C.glDrawBuffers(identityDrawBuffers);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                restore(state);
                warnUnavailable("Nvidium's active framebuffer cannot accept CAUSTIQ G-buffer attachments (status 0x"
                        + Integer.toHexString(status) + ").");
                return false;
            }
            if (clearAttachments) clearAuxiliaryAttachments();
            GL20C.glDrawBuffers(drawBuffers);
            activeState = state;
            warnedUnavailable = false;
            return true;
        } catch (RuntimeException exception) {
            restore(state);
            throw exception;
        }
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

    /** Returns the terrain-produced GL texture for an auxiliary color attachment (1 through 7). */
    public static int texture(int attachment) {
        if (attachment < NORMAL || attachment > MAX_ATTACHMENT) {
            throw new IllegalArgumentException("G-buffer attachment must be between 1 and " + MAX_ATTACHMENT + ".");
        }
        return TEXTURES[attachment - 1];
    }

    /** Alternate storage used by Iris deferred/composite ping-pong passes. */
    public static int alternateTexture(int attachment) {
        if (attachment < NORMAL || attachment > MAX_ATTACHMENT) {
            throw new IllegalArgumentException("G-buffer attachment must be between 1 and " + MAX_ATTACHMENT + ".");
        }
        return ALTERNATE_TEXTURES[attachment - 1];
    }

    /** Minecraft GPU view for sampling this attachment in a RenderPass. */
    public static GpuTextureView view(int attachment) {
        if (attachment < NORMAL || attachment > MAX_ATTACHMENT) {
            throw new IllegalArgumentException("G-buffer attachment must be between 1 and " + MAX_ATTACHMENT + ".");
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
            if (ALTERNATE_TARGETS[i] != null) ALTERNATE_TARGETS[i].destroyBuffers();
            TARGETS[i] = null;
            ALTERNATE_TARGETS[i] = null;
            TEXTURES[i] = 0;
            ALTERNATE_TEXTURES[i] = 0;
        }
        width = 0;
        height = 0;
        targetSpecs = Map.of();
        resetOutputRouting();
        formatsDirty = true;
    }

    private static void ensureTargets(int newWidth, int newHeight) {
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        if (TARGETS[0] == null) {
            for (int i = 0; i < TARGETS.length; i++) {
                TARGETS[i] = new TextureTarget("Caustiq Nvidium G-buffer " + (i + 1),
                        newWidth, newHeight, false);
                ALTERNATE_TARGETS[i] = new TextureTarget("Caustiq Iris ping-pong " + (i + 1),
                        newWidth, newHeight, false);
            }
        } else if (width != newWidth || height != newHeight) {
            for (int i = 0; i < TARGETS.length; i++) {
                TARGETS[i].resize(newWidth, newHeight);
                ALTERNATE_TARGETS[i].resize(newWidth, newHeight);
            }
        }
        int previousActiveTexture = GL11C.glGetInteger(GL13_ACTIVE_TEXTURE);
        int previousTextureBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            for (int i = 0; i < TARGETS.length; i++) {
                int index = i + 1;
                IrisTerrainPassScheduler.RenderTarget spec = targetSpecs.get(index);
                if (spec != null && (spec.scaleX() != 1.0f || spec.scaleY() != 1.0f)) {
                    throw new IllegalArgumentException("Scaled colortex" + index
                            + " cannot be attached to Nvidium's fixed-size terrain framebuffer.");
                }
                FormatDescriptor format = format(spec == null ? "RGBA16F" : spec.format());
                boolean nearest = spec != null && spec.nearest();
                boolean mipmap = spec != null && spec.mipmap();
                TEXTURES[i] = ((GlTexture) TARGETS[i].getColorTexture()).glId();
                ALTERNATE_TEXTURES[i] = ((GlTexture) ALTERNATE_TARGETS[i].getColorTexture()).glId();
                configureTexture(TEXTURES[i], format, nearest, mipmap, newWidth, newHeight,
                        formatsDirty || width != newWidth || height != newHeight);
                configureTexture(ALTERNATE_TEXTURES[i], format, nearest, mipmap, newWidth, newHeight,
                        formatsDirty || width != newWidth || height != newHeight);
            }
            width = newWidth;
            height = newHeight;
            formatsDirty = false;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTextureBinding);
            GL13C.glActiveTexture(previousActiveTexture);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    private static void configureTexture(int texture, FormatDescriptor format, boolean nearest,
                                         boolean mipmap, int textureWidth, int textureHeight,
                                         boolean allocateStorage) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                mipmap ? GL11C.GL_LINEAR_MIPMAP_LINEAR : (nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR));
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
        if (allocateStorage) {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format.internalFormat(),
                    textureWidth, textureHeight, 0, format.externalFormat(), format.dataType(),
                    (java.nio.ByteBuffer) null);
        }
    }

    private static void clearAuxiliaryAttachments() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < ATTACHMENT_COUNT; i++) {
                int index = i + 1;
                IrisTerrainPassScheduler.RenderTarget spec = targetSpecs.get(index);
                if (spec != null && !spec.clear()) continue;
                float[] color = parseClearColor(spec == null ? "0.0 0.0 0.0 0.0" : spec.clearColor());
                FloatBuffer clearValue = stack.mallocFloat(4);
                clearValue.put(color).flip();
                GL30C.glClearBufferfv(GL11C.GL_COLOR, index, clearValue);
            }
        }
    }

    private static float[] parseClearColor(String value) {
        String[] components = value.trim().split("[\\s,]+");
        if (components.length == 1) {
            float gray = Float.parseFloat(components[0]);
            return new float[]{gray, gray, gray, 1.0f};
        }
        if (components.length != 3 && components.length != 4) {
            throw new IllegalArgumentException("Invalid Iris colortex clear color: " + value);
        }
        float[] result = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        for (int i = 0; i < components.length; i++) result[i] = Float.parseFloat(components[i]);
        return result;
    }

    private static FormatDescriptor format(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "RGBA8" -> new FormatDescriptor(GL30C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE);
            case "RGBA16F" -> new FormatDescriptor(GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL30C.GL_HALF_FLOAT);
            case "RGBA32F" -> new FormatDescriptor(GL30C.GL_RGBA32F, GL11C.GL_RGBA, GL11C.GL_FLOAT);
            case "RGB8" -> new FormatDescriptor(GL30C.GL_RGB8, GL11C.GL_RGB, GL11C.GL_UNSIGNED_BYTE);
            case "RGB16F" -> new FormatDescriptor(GL30C.GL_RGB16F, GL11C.GL_RGB, GL30C.GL_HALF_FLOAT);
            case "RGB32F" -> new FormatDescriptor(GL30C.GL_RGB32F, GL11C.GL_RGB, GL30C.GL_FLOAT);
            case "RG8" -> new FormatDescriptor(GL30C.GL_RG8, GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE);
            case "RG16F" -> new FormatDescriptor(GL30C.GL_RG16F, GL30C.GL_RG, GL30C.GL_HALF_FLOAT);
            case "RG32F" -> new FormatDescriptor(GL30C.GL_RG32F, GL30C.GL_RG, GL30C.GL_FLOAT);
            case "R8" -> new FormatDescriptor(GL30C.GL_R8, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE);
            case "R16F" -> new FormatDescriptor(GL30C.GL_R16F, GL11C.GL_RED, GL30C.GL_HALF_FLOAT);
            case "R32F" -> new FormatDescriptor(GL30C.GL_R32F, GL11C.GL_RED, GL30C.GL_FLOAT);
            case "RGB10_A2" -> new FormatDescriptor(GL30C.GL_RGB10_A2, GL11C.GL_RGBA, GL30C.GL_UNSIGNED_INT_2_10_10_10_REV);
            case "R11F_G11F_B10F" -> new FormatDescriptor(GL30C.GL_R11F_G11F_B10F, GL11C.GL_RGB,
                    GL30C.GL_UNSIGNED_INT_10F_11F_11F_REV);
            default -> throw new IllegalArgumentException("Unsupported Iris colortex format: " + name);
        };
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

    private record FormatDescriptor(int internalFormat, int externalFormat, int dataType) { }
    private record AttachmentState(int type, int object, int level, int cubeFace) { }
    private record FramebufferState(int framebuffer, int[] drawBuffers, AttachmentState[] attachments) {
        private FramebufferState {
            drawBuffers = Arrays.copyOf(drawBuffers, drawBuffers.length);
            attachments = Arrays.copyOf(attachments, attachments.length);
        }
    }
}
