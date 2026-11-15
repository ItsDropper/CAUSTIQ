package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes Iris full-screen passes with real OpenGL MRT attachments. Terrain
 * MRT data is produced by Nvidium; this executor consumes and updates the same
 * colortex attachments in scheduled composite/deferred/final order.
 */
public final class IrisGlPassExecutor implements AutoCloseable {
    private static final int MAX_COLOR_TARGET = NvidiumGBufferTargets.MAX_ATTACHMENT;
    private int framebuffer;
    private int vertexArray;
    private TextureTarget outputTarget;
    private final TextureTarget[][] scaledTargets = new TextureTarget[MAX_COLOR_TARGET + 1][2];
    private final int[][] scaledWidths = new int[MAX_COLOR_TARGET + 1][2];
    private final int[][] scaledHeights = new int[MAX_COLOR_TARGET + 1][2];
    private final String[][] scaledFormats = new String[MAX_COLOR_TARGET + 1][2];
    private int width;
    private int height;

    public void render(IrisTerrainPassScheduler.Plan plan,
                       Map<String, IrisGlProgram> programs,
                       RenderTarget mainTarget) {
        if (plan == null || plan.isEmpty() || mainTarget == null || programs.isEmpty()) return;
        int targetWidth = mainTarget.width;
        int targetHeight = mainTarget.height;
        if (targetWidth <= 0 || targetHeight <= 0) return;
        GlState previous = captureState();
        boolean[] alternateCurrent = new boolean[MAX_COLOR_TARGET + 1];
        boolean[] hasWritten = new boolean[MAX_COLOR_TARGET + 1];
        try {
            Set<Integer> fullscreenWrites = new HashSet<>();
            for (IrisTerrainPassScheduler.ScheduledPass scheduled : plan.passes()) {
                if (scheduled.pass().group() != ShaderPackProgramCatalog.Group.GBUFFERS) {
                    fullscreenWrites.addAll(scheduled.pass().writes());
                }
            }
            ensureResources(targetWidth, targetHeight, plan.targets(), fullscreenWrites);
            for (IrisTerrainPassScheduler.ScheduledPass scheduled : plan.passes()) {
                IrisTerrainPassScheduler.Pass pass = scheduled.pass();
                if (pass.group() == ShaderPackProgramCatalog.Group.GBUFFERS) continue;
                if (pass.group() != ShaderPackProgramCatalog.Group.DEFERRED
                        && pass.group() != ShaderPackProgramCatalog.Group.COMPOSITE
                        && pass.group() != ShaderPackProgramCatalog.Group.FINAL) {
                    throw new IllegalStateException("Iris pass group '" + pass.group()
                            + "' is not supported by the full-screen executor.");
                }
                if (pass.group() == ShaderPackProgramCatalog.Group.SHADOW) {
                    throw new IllegalStateException("Iris shadow rendering requires a light-space Nvidium terrain pass.");
                }
                if (pass.compute() || pass.geometryOrTessellation()) {
                    throw new IllegalStateException("Iris pass '" + pass.name()
                            + "' requires compute/geometry execution, which is not a full-screen pass.");
                }
                IrisGlProgram program = programs.get(pass.name());
                if (program == null) {
                    throw new IllegalStateException("Scheduled Iris pass has no linked program: " + pass.name());
                }
                renderPass(pass, plan.targets(), program, mainTarget, targetWidth, targetHeight, alternateCurrent, hasWritten);
            }
            if (hasWritten[0] && alternateCurrent[0]) {
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                        outputTarget.getColorTexture(),
                        mainTarget.getColorTexture(),
                        0, 0, 0, 0, 0,
                        targetWidth, targetHeight);
            }
        } finally {
            restoreState(previous);
        }
    }

    private void renderPass(IrisTerrainPassScheduler.Pass pass,
                            Map<Integer, IrisTerrainPassScheduler.RenderTarget> targets,
                            IrisGlProgram program,
                            RenderTarget mainTarget,
                            int targetWidth,
                            int targetHeight,
                            boolean[] alternateCurrent,
                            boolean[] hasWritten) {
        Set<Integer> writes = pass.writes().isEmpty() ? Set.of(0) : pass.writes();
        for (int location : writes) {
            if (location < 0 || location > MAX_COLOR_TARGET) {
                throw new IllegalStateException("Iris pass '" + pass.name()
                        + "' writes colortex" + location + "; this Nvidium path exposes colortex0-colortex7.");
            }
            IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
            if (location == 0 && target != null
                    && (target.scaleX() != 1.0f || target.scaleY() != 1.0f)) {
                throw new IllegalStateException("Iris colortex0 must remain at the main framebuffer size.");
            }
            if (target != null && location > 0
                    && !target.format().equalsIgnoreCase("RGBA16F")
                    && !target.format().equalsIgnoreCase("RGBA8")) {
                throw new IllegalStateException("Iris colortex" + location + " format '" + target.format()
                        + "' is not supported by the current Nvidium attachment allocation.");
            }
        }

        for (int location : pass.reads()) {
            if (location < 0 || location > MAX_COLOR_TARGET) {
                throw new IllegalStateException("Iris pass '" + pass.name()
                        + "' reads colortex" + location
                        + "; this Nvidium path exposes colortex0-colortex7.");
            }
        }

        Map<Integer, Integer> outputTargets = pass.outputTargets().isEmpty()
                ? Map.of(0, 0) : pass.outputTargets();
        int[] drawBuffers = createDrawBuffers(outputTargets, writes);
        IrisRenderTargetSize outputSize = outputSize(writes, targets, targetWidth, targetHeight);
        if (drawBuffers.length > GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS)) {
            throw new IllegalStateException("Iris pass '" + pass.name()
                    + "' requires more draw-buffer slots than this OpenGL context exposes.");
        }
        int maxColorAttachments = GL11C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS);
        if (writes.stream().anyMatch(location -> location >= maxColorAttachments)) {
            throw new IllegalStateException("Iris pass '" + pass.name()
                    + "' writes a color attachment beyond this OpenGL context's limit.");
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        for (int location = 0; location <= MAX_COLOR_TARGET; location++) {
            if (writes.contains(location)) {
                int texture = writeTexture(location, mainTarget, alternateCurrent, hasWritten);
                if (texture == 0) {
                    throw new IllegalStateException("Iris pass '" + pass.name()
                            + "' has no allocated colortex" + location + " texture.");
                }
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,
                        GL30C.GL_COLOR_ATTACHMENT0 + location, GL11C.GL_TEXTURE_2D, texture, 0);
            } else {
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,
                        GL30C.GL_COLOR_ATTACHMENT0 + location, GL11C.GL_TEXTURE_2D, 0, 0);
            }
        }
        GL20C.glDrawBuffers(drawBuffers);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Iris pass '" + pass.name()
                    + "' framebuffer is incomplete (0x" + Integer.toHexString(status) + ").");
        }

        GL11C.glViewport(0, 0, outputSize.width(), outputSize.height());
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        clearOutputs(outputTargets, targets);

        GL20C.glUseProgram(program.programId());
        bindSamplers(program, mainTarget, alternateCurrent, hasWritten);
        uploadScreenUniforms(program.programId(), outputSize.width(), outputSize.height());
        NvidiumIrisUniformBridge.uploadProgram(program.programId());
        GL30C.glBindVertexArray(vertexArray);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        for (int location : writes) {
            alternateCurrent[location] = !alternateCurrent[location];
            hasWritten[location] = true;
            IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
            if (location > 0 && target != null && target.mipmap()) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                        currentTexture(location, mainTarget, alternateCurrent, hasWritten));
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            }
        }
    }

    private static void clearOutputs(Map<Integer, Integer> outputTargets,
                                     Map<Integer, IrisTerrainPassScheduler.RenderTarget> targets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (Map.Entry<Integer, Integer> output : outputTargets.entrySet()) {
                int drawBuffer = output.getKey();
                int targetIndex = output.getValue();
                IrisTerrainPassScheduler.RenderTarget target = targets.get(targetIndex);
                if (target != null && !target.clear()) continue;
                float[] rgba = parseClearColor(target == null ? "0.0 0.0 0.0 0.0" : target.clearColor());
                FloatBuffer value = stack.mallocFloat(4);
                value.put(rgba).flip();
                GL30C.glClearBufferfv(GL11C.GL_COLOR, drawBuffer, value);
            }
        }
    }

    static int[] createDrawBuffers(Map<Integer, Integer> outputTargets, Set<Integer> writes) {
        if (outputTargets.isEmpty()) return new int[]{GL30C.GL_COLOR_ATTACHMENT0};
        int maxSlot = outputTargets.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (maxSlot < 0) throw new IllegalArgumentException("Iris fragment output slots cannot be negative.");
        int[] result = new int[maxSlot + 1];
        Arrays.fill(result, GL11C.GL_NONE);
        outputTargets.forEach((slot, target) -> {
            if (slot < 0 || target < 0 || !writes.contains(target)) {
                throw new IllegalStateException("Invalid Iris fragment output mapping: slot "
                        + slot + " -> colortex" + target + ".");
            }
            result[slot] = GL30C.GL_COLOR_ATTACHMENT0 + target;
        });
        return result;
    }

    private static IrisRenderTargetSize outputSize(
            Set<Integer> writes, Map<Integer, IrisTerrainPassScheduler.RenderTarget> targets,
            int baseWidth, int baseHeight) {
        IrisRenderTargetSize result = null;
        for (int location : writes) {
            IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
            float scaleX = target == null ? 1.0f : target.scaleX();
            float scaleY = target == null ? 1.0f : target.scaleY();
            IrisRenderTargetSize size = IrisRenderTargetSize.of(baseWidth, baseHeight, scaleX, scaleY);
            if (result != null && !result.equals(size)) {
                throw new IllegalStateException("A single Iris pass cannot write render targets with different dimensions: "
                        + result.width() + "x" + result.height() + " and "
                        + size.width() + "x" + size.height() + ".");
            }
            result = size;
        }
        return result == null ? IrisRenderTargetSize.of(baseWidth, baseHeight, 1.0f, 1.0f) : result;
    }

    private static float[] parseClearColor(String value) {
        String[] components = value.trim().split("[\\s,]+");
        if (components.length == 1) {
            float gray = Float.parseFloat(components[0]);
            return new float[]{gray, gray, gray, 1.0f};
        }
        if (components.length != 3 && components.length != 4) {
            throw new IllegalArgumentException("Iris clearColor must contain one, three, or four components: " + value);
        }
        float[] result = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        for (int i = 0; i < components.length; i++) result[i] = Float.parseFloat(components[i]);
        return result;
    }

    private void bindSamplers(IrisGlProgram program, RenderTarget mainTarget,
                              boolean[] alternateCurrent, boolean[] hasWritten) {
        int unit = 0;
        for (String sampler : program.samplers()) {
            int location = GL20C.glGetUniformLocation(program.programId(), sampler);
            if (location < 0) continue;
            int texture;
            if (sampler.equals("gcolor") || sampler.equals("DiffuseSampler")
                    || sampler.equals("texture") || sampler.equals("tex")
                    || sampler.equals("colortex0")) {
                texture = currentTexture(0, mainTarget, alternateCurrent, hasWritten);
            } else if (sampler.startsWith("colortex")) {
                int index;
                try {
                    index = Integer.parseInt(sampler.substring("colortex".length()));
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("Unsupported Iris sampler name: " + sampler, exception);
                }
                if (index < 1 || index > MAX_COLOR_TARGET) {
                    throw new IllegalStateException("Iris sampler '" + sampler
                            + "' is outside the Nvidium colortex0-colortex7 range.");
                }
                texture = currentTexture(index, mainTarget, alternateCurrent, hasWritten);
            } else {
                throw new IllegalStateException("Iris sampler '" + sampler
                        + "' has no CAUSTIQ texture binding.");
            }
            if (unit >= GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS)) {
                throw new IllegalStateException("Iris pass '" + program.name()
                        + "' exceeds the fragment-stage texture-unit limit.");
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL20C.glUniform1i(location, unit);
            unit++;
        }
    }

    private int writeTexture(int location, RenderTarget mainTarget,
                              boolean[] alternateCurrent, boolean[] hasWritten) {
        if (location == 0) {
            return !hasWritten[0] || !alternateCurrent[0]
                    ? textureId(outputTarget.getColorTexture())
                    : textureId(mainTarget.getColorTexture());
        }
        if (scaledTargets[location][0] != null) {
            int side = !hasWritten[location] ? 0 : (alternateCurrent[location] ? 1 : 0);
            return textureId(scaledTargets[location][side].getColorTexture());
        }
        return !hasWritten[location] || !alternateCurrent[location]
                ? NvidiumGBufferTargets.alternateTexture(location)
                : NvidiumGBufferTargets.texture(location);
    }

    private int currentTexture(int location, RenderTarget mainTarget,
                               boolean[] alternateCurrent, boolean[] hasWritten) {
        if (location == 0) {
            return !hasWritten[0] || !alternateCurrent[0]
                    ? textureId(mainTarget.getColorTexture())
                    : textureId(outputTarget.getColorTexture());
        }
        if (scaledTargets[location][0] != null) {
            if (!hasWritten[location]) return NvidiumGBufferTargets.texture(location);
            int side = alternateCurrent[location] ? 0 : 1;
            return textureId(scaledTargets[location][side].getColorTexture());
        }
        return !hasWritten[location] || !alternateCurrent[location]
                ? NvidiumGBufferTargets.texture(location)
                : NvidiumGBufferTargets.alternateTexture(location);
    }

    private static void uploadScreenUniforms(int program, int width, int height) {
        set2f(program, "viewSize", width, height);
        set2f(program, "pixelSize", 1.0f / width, 1.0f / height);
        set1f(program, "viewWidth", width);
        set1f(program, "viewHeight", height);
    }

    private static void set2f(int program, String name, float x, float y) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform2f(location, x, y);
    }

    private static void set1f(int program, String name, float value) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1f(location, value);
    }

    private void ensureResources(int targetWidth, int targetHeight,
                                 Map<Integer, IrisTerrainPassScheduler.RenderTarget> targets,
                                 Set<Integer> fullscreenWrites) {
        if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
        if (vertexArray == 0) vertexArray = GL30C.glGenVertexArrays();
        if (outputTarget == null) {
            outputTarget = new TextureTarget("Caustiq Iris MRT pass", targetWidth, targetHeight, false);
            width = targetWidth;
            height = targetHeight;
        } else if (width != targetWidth || height != targetHeight) {
            outputTarget.resize(targetWidth, targetHeight);
            width = targetWidth;
            height = targetHeight;
        }

        IrisTerrainPassScheduler.RenderTarget mainSpec = targets.get(0);
        if (mainSpec != null && (mainSpec.scaleX() != 1.0f || mainSpec.scaleY() != 1.0f)) {
            throw new IllegalStateException("Iris colortex0 must remain at the main framebuffer size.");
        }
        for (int index = 1; index <= MAX_COLOR_TARGET; index++) {
            IrisTerrainPassScheduler.RenderTarget spec = targets.get(index);
            float scaleX = spec == null ? 1.0f : spec.scaleX();
            float scaleY = spec == null ? 1.0f : spec.scaleY();
            if (!fullscreenWrites.contains(index) || (scaleX == 1.0f && scaleY == 1.0f)) {
                destroyScaledTargets(index);
                continue;
            }
            if (spec != null && !spec.format().equalsIgnoreCase("RGBA8")
                    && !spec.format().equalsIgnoreCase("RGBA16F")) {
                throw new IllegalStateException("Scaled Iris colortex" + index + " format '"
                        + spec.format() + "' is not supported by the scaled pass allocator.");
            }
            IrisRenderTargetSize size = IrisRenderTargetSize.of(targetWidth, targetHeight, scaleX, scaleY);
            String format = spec == null ? "RGBA8" : spec.format().toUpperCase(java.util.Locale.ROOT);
            boolean nearest = spec != null && spec.nearest();
            boolean mipmap = spec != null && spec.mipmap();
            for (int side = 0; side < 2; side++) {
                TextureTarget target = scaledTargets[index][side];
                boolean allocate = target == null || scaledWidths[index][side] != size.width()
                        || scaledHeights[index][side] != size.height()
                        || !format.equals(scaledFormats[index][side]);
                if (target == null) {
                    target = new TextureTarget("Caustiq scaled colortex" + index + " ping-pong " + side,
                            size.width(), size.height(), false);
                    scaledTargets[index][side] = target;
                } else if (scaledWidths[index][side] != size.width()
                        || scaledHeights[index][side] != size.height()) {
                    target.resize(size.width(), size.height());
                }
                int texture = textureId(target.getColorTexture());
                configureScaledTexture(texture, format, nearest, mipmap,
                        size.width(), size.height(), allocate);
                scaledWidths[index][side] = size.width();
                scaledHeights[index][side] = size.height();
                scaledFormats[index][side] = format;
            }
        }
    }

    private static void configureScaledTexture(int texture, String format, boolean nearest,
                                               boolean mipmap, int targetWidth, int targetHeight,
                                               boolean allocateStorage) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                mipmap ? GL11C.GL_LINEAR_MIPMAP_LINEAR : (nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR));
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 0x812F);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 0x812F);
        if (allocateStorage) {
            int internal = format.equalsIgnoreCase("RGBA16F") ? GL30C.GL_RGBA16F : GL30C.GL_RGBA8;
            int type = format.equalsIgnoreCase("RGBA16F") ? GL30C.GL_HALF_FLOAT : GL11C.GL_UNSIGNED_BYTE;
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internal,
                    targetWidth, targetHeight, 0, GL11C.GL_RGBA, type, (java.nio.ByteBuffer) null);
        }
    }

    private void destroyScaledTargets(int index) {
        for (int side = 0; side < 2; side++) {
            if (scaledTargets[index][side] != null) scaledTargets[index][side].destroyBuffers();
            scaledTargets[index][side] = null;
            scaledWidths[index][side] = 0;
            scaledHeights[index][side] = 0;
            scaledFormats[index][side] = null;
        }
    }

    private static int textureId(Object texture) {
        return texture instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }

    private static GlState captureState() {
        int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int vertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int readBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
        int drawBufferCount = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
        int[] drawBuffers = new int[drawBufferCount];
        for (int i = 0; i < drawBufferCount; i++) {
            drawBuffers[i] = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0 + i);
        }
        int textureUnits = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        int[] textureBindings = new int[textureUnits];
        for (int i = 0; i < textureUnits; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            textureBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        }
        GL13C.glActiveTexture(activeTexture);
        int[] viewport = new int[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, values);
            values.get(viewport);
        }
        return new GlState(drawFramebuffer, readFramebuffer, program, vertexArray, activeTexture,
                readBuffer, drawBuffers, textureBindings, viewport,
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST));
    }

    private static void restoreState(GlState state) {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.drawFramebuffer());
        GL20C.glDrawBuffers(state.drawBuffers());
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, state.readFramebuffer());
        GL11C.glReadBuffer(state.readBuffer());
        GL20C.glUseProgram(state.program());
        GL30C.glBindVertexArray(state.vertexArray());
        GL11C.glViewport(state.viewport()[0], state.viewport()[1], state.viewport()[2], state.viewport()[3]);
        for (int i = 0; i < state.textureBindings().length; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.textureBindings()[i]);
        }
        GL13C.glActiveTexture(state.activeTexture());
        setEnabled(GL11C.GL_DEPTH_TEST, state.depthTest());
        setEnabled(GL11C.GL_BLEND, state.blend());
        setEnabled(GL11C.GL_CULL_FACE, state.cullFace());
        setEnabled(GL11C.GL_SCISSOR_TEST, state.scissorTest());
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    @Override
    public void close() {
        if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
        if (vertexArray != 0) GL30C.glDeleteVertexArrays(vertexArray);
        framebuffer = 0;
        vertexArray = 0;
        if (outputTarget != null) outputTarget.destroyBuffers();
        outputTarget = null;
        for (int index = 1; index <= MAX_COLOR_TARGET; index++) destroyScaledTargets(index);
        width = 0;
        height = 0;
    }

    private record GlState(int drawFramebuffer, int readFramebuffer, int program, int vertexArray,
                           int activeTexture, int readBuffer, int[] drawBuffers, int[] textureBindings,
                           int[] viewport, boolean depthTest, boolean blend, boolean cullFace,
                           boolean scissorTest) { }
}
