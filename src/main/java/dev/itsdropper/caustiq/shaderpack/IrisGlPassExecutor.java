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
    private int width;
    private int height;

    public void render(IrisTerrainPassScheduler.Plan plan,
                       Map<String, IrisGlProgram> programs,
                       RenderTarget mainTarget) {
        if (plan == null || plan.isEmpty() || mainTarget == null || programs.isEmpty()) return;
        int targetWidth = mainTarget.width;
        int targetHeight = mainTarget.height;
        if (targetWidth <= 0 || targetHeight <= 0) return;
        ensureResources(targetWidth, targetHeight);

        GlState previous = captureState();
        boolean[] alternateCurrent = new boolean[MAX_COLOR_TARGET + 1];
        try {
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
                renderPass(pass, plan.targets(), program, mainTarget, targetWidth, targetHeight, alternateCurrent);
            }
            if (alternateCurrent[0]) {
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
                            boolean[] alternateCurrent) {
        Set<Integer> writes = pass.writes().isEmpty() ? Set.of(0) : pass.writes();
        for (int location : writes) {
            if (location < 0 || location > MAX_COLOR_TARGET) {
                throw new IllegalStateException("Iris pass '" + pass.name()
                        + "' writes colortex" + location + "; this Nvidium path exposes colortex0-colortex7.");
            }
            if (location > 0 && pass.reads().contains(location)) {
                throw new IllegalStateException("Iris pass '" + pass.name() + "' reads and writes colortex"
                        + location + " in one draw; a ping-pong target is required.");
            }
            IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
            if (target != null && (target.scaleX() != 1.0f || target.scaleY() != 1.0f)) {
                throw new IllegalStateException("Iris pass '" + pass.name()
                        + "' uses a scaled colortex" + location + "; scaled MRT allocation is not active.");
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

        Map<Integer, Integer> outputTargets = pass.outputTargets();
        int maxOutputSlot = outputTargets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int[] drawBuffers = new int[maxOutputSlot + 1];
        java.util.Arrays.fill(drawBuffers, GL11C.GL_NONE);
        if (outputTargets.isEmpty()) {
            drawBuffers[0] = GL30C.GL_COLOR_ATTACHMENT0;
        } else {
            outputTargets.forEach((slot, target) -> {
                if (slot < 0 || slot >= drawBuffers.length || !writes.contains(target)) {
                    throw new IllegalStateException("Iris pass '" + pass.name()
                            + "' has an invalid fragment output mapping: slot " + slot
                            + " -> colortex" + target + ".");
                }
                drawBuffers[slot] = GL30C.GL_COLOR_ATTACHMENT0 + target;
            });
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        for (int location = 0; location <= MAX_COLOR_TARGET; location++) {
            if (writes.contains(location)) {
                int texture = writeTexture(location, mainTarget, alternateCurrent);
                if (texture == 0) {
                    throw new IllegalStateException("Iris pass '" + pass.name()
                            + "' has no allocated colortex" + location + " texture.");
                }
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,
                        GL30C.GL_COLOR_ATTACHMENT0 + location, GL11C.GL_TEXTURE_2D, texture, 0);
                drawBuffers[location] = GL30C.GL_COLOR_ATTACHMENT0 + location;
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

        GL11C.glViewport(0, 0, targetWidth, targetHeight);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        clearOutputs(writes, targets);

        GL20C.glUseProgram(program.programId());
        bindSamplers(program, mainTarget, alternateCurrent);
        uploadScreenUniforms(program.programId(), targetWidth, targetHeight);
        NvidiumIrisUniformBridge.uploadProgram(program.programId());
        GL30C.glBindVertexArray(vertexArray);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        for (int location : writes) {
            alternateCurrent[location] = !alternateCurrent[location];
            IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
            if (location > 0 && target != null && target.mipmap()) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                        currentTexture(location, mainTarget, alternateCurrent));
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            }
        }
    }

    private static void clearOutputs(Set<Integer> writes,
                                     Map<Integer, IrisTerrainPassScheduler.RenderTarget> targets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int location : writes) {
                IrisTerrainPassScheduler.RenderTarget target = targets.get(location);
                if (target != null && !target.clear()) continue;
                float[] rgba = parseClearColor(target == null ? "0.0 0.0 0.0 0.0" : target.clearColor());
                FloatBuffer value = stack.mallocFloat(4);
                value.put(rgba).flip();
                GL30C.glClearBufferfv(GL11C.GL_COLOR, location, value);
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
            throw new IllegalArgumentException("Iris clearColor must contain one, three, or four components: " + value);
        }
        float[] result = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        for (int i = 0; i < components.length; i++) result[i] = Float.parseFloat(components[i]);
        return result;
    }

    private void bindSamplers(IrisGlProgram program, RenderTarget mainTarget,
                              boolean[] alternateCurrent) {
        int unit = 0;
        for (String sampler : program.samplers()) {
            int location = GL20C.glGetUniformLocation(program.programId(), sampler);
            if (location < 0) continue;
            int texture;
            if (sampler.equals("gcolor") || sampler.equals("DiffuseSampler")
                    || sampler.equals("texture") || sampler.equals("tex")
                    || sampler.equals("colortex0")) {
                texture = currentTexture(0, mainTarget, alternateCurrent);
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
                texture = currentTexture(index, mainTarget, alternateCurrent);
            } else {
                throw new IllegalStateException("Iris sampler '" + sampler
                        + "' has no CAUSTIQ texture binding.");
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL20C.glUniform1i(location, unit);
            unit++;
        }
    }

    private int writeTexture(int location, RenderTarget mainTarget, boolean[] alternateCurrent) {
        if (location == 0) {
            return alternateCurrent[0]
                    ? textureId(mainTarget.getColorTexture())
                    : textureId(outputTarget.getColorTexture());
        }
        return alternateCurrent[location]
                ? NvidiumGBufferTargets.texture(location)
                : NvidiumGBufferTargets.alternateTexture(location);
    }

    private int currentTexture(int location, RenderTarget mainTarget, boolean[] alternateCurrent) {
        if (location == 0) {
            return alternateCurrent[0]
                    ? textureId(outputTarget.getColorTexture())
                    : textureId(mainTarget.getColorTexture());
        }
        return alternateCurrent[location]
                ? NvidiumGBufferTargets.alternateTexture(location)
                : NvidiumGBufferTargets.texture(location);
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

    private void ensureResources(int targetWidth, int targetHeight) {
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
        int textureUnits = Math.min(8, GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS));
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
        width = 0;
        height = 0;
    }

    private record GlState(int drawFramebuffer, int readFramebuffer, int program, int vertexArray,
                           int activeTexture, int readBuffer, int[] drawBuffers, int[] textureBindings,
                           int[] viewport, boolean depthTest, boolean blend, boolean cullFace,
                           boolean scissorTest) { }
}
