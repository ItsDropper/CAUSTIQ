package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Executes a conventional full-screen program against Minecraft's completed color target.
 * It never changes terrain submissions or chunk-renderer state.
 */
public final class ShaderPostProcessPass implements AutoCloseable {
    private static final float[] TRIANGLE = {
            -1.0f, -1.0f, 0.0f, 1.0f, 0.0f, 0.0f,
             3.0f, -1.0f, 0.0f, 1.0f, 2.0f, 0.0f,
            -1.0f,  3.0f, 0.0f, 1.0f, 0.0f, 2.0f
    };

    private int framebuffer;
    private int colorTexture;
    private int vertexArray;
    private int vertexBuffer;
    private int width;
    private int height;

    public void render(RenderTarget mainTarget, CompiledShaderProgram program) {
        if (mainTarget == null || program == null) return;
        int targetWidth = mainTarget.width;
        int targetHeight = mainTarget.height;
        int sourceTexture = mainTarget.getColorTextureId();
        if (targetWidth <= 0 || targetHeight <= 0 || sourceTexture == 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int previousVertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int previousArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetInteger(GL11C.GL_VIEWPORT, viewport);

            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            int previousTexture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
            boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
            boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
            boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
            ByteBuffer colorMask = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMask);

            try {
                ensureResources(targetWidth, targetHeight);
                if (sourceTexture == colorTexture) return;

                // Bind Minecraft's real output target rather than assuming framebuffer zero.
                mainTarget.bindWrite(false);
                int destinationFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);

                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL11C.glViewport(0, 0, width, height);
                GL11C.glDisable(GL11C.GL_BLEND);
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                GL11C.glDisable(GL11C.GL_CULL_FACE);
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDepthMask(false);
                GL11C.glColorMask(true, true, true, true);

                GL20C.glUseProgram(program.programId());
                GL30C.glBindVertexArray(vertexArray);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sourceTexture);
                uploadCommonUniforms(program, stack, targetWidth, targetHeight);
                program.setSampler("DiffuseSampler", 0);
                program.setSampler("colortex0", 0);
                program.setSampler("gcolor", 0);
                program.setSampler("texture", 0);
                program.setSampler("tex", 0);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
                GL30C.glBlitFramebuffer(0, 0, width, height,
                        0, 0, targetWidth, targetHeight,
                        GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            } finally {
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
                GL20C.glUseProgram(previousProgram);
                GL30C.glBindVertexArray(previousVertexArray);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArrayBuffer);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture0);
                GL13C.glActiveTexture(previousActiveTexture);
                GL11C.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                setEnabled(GL11C.GL_BLEND, blend);
                setEnabled(GL11C.GL_DEPTH_TEST, depth);
                setEnabled(GL11C.GL_CULL_FACE, cull);
                setEnabled(GL11C.GL_SCISSOR_TEST, scissor);
                GL11C.glDepthMask(depthMask);
                GL11C.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                        colorMask.get(2) != 0, colorMask.get(3) != 0);
            }
        }
    }

    private void ensureResources(int targetWidth, int targetHeight) {
        if (framebuffer != 0 && width == targetWidth && height == targetHeight) return;
        destroyTargets();
        width = targetWidth;
        height = targetHeight;

        colorTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorTexture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8,
                width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, colorTexture, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            destroyTargets();
            throw new IllegalStateException("Caustiq post-process framebuffer is incomplete: 0x"
                    + Integer.toHexString(status));
        }

        vertexArray = GL30C.glGenVertexArrays();
        vertexBuffer = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vertexArray);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertexBuffer);
        FloatBuffer vertices = BufferUtils.createFloatBuffer(TRIANGLE.length);
        vertices.put(TRIANGLE).flip();
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 4, GL11C.GL_FLOAT, false, 6 * Float.BYTES, 0L);
        GL20C.glEnableVertexAttribArray(1);
        GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 6 * Float.BYTES, 4L * Float.BYTES);
        GL30C.glBindVertexArray(0);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private static final int GL_CLAMP_TO_EDGE = 0x812F;

    private static void uploadCommonUniforms(
            CompiledShaderProgram program,
            MemoryStack stack,
            int targetWidth,
            int targetHeight
    ) {
        FloatBuffer identity = stack.mallocFloat(16);
        identity.put(new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        }).flip();
        program.setMatrix4("ProjMat", identity);
        identity.rewind();
        program.setMatrix4("ModelViewMat", identity);
        program.setVec2("InSize", targetWidth, targetHeight);
        program.setVec2("OutSize", targetWidth, targetHeight);
        program.setVec2("ScreenSize", targetWidth, targetHeight);
        program.setFloat("viewWidth", targetWidth);
        program.setFloat("viewHeight", targetHeight);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    private void destroyTargets() {
        if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
        if (colorTexture != 0) GL11C.glDeleteTextures(colorTexture);
        framebuffer = 0;
        colorTexture = 0;
        width = 0;
        height = 0;
    }

    @Override
    public void close() {
        destroyTargets();
        if (vertexBuffer != 0) GL15C.glDeleteBuffers(vertexBuffer);
        if (vertexArray != 0) GL30C.glDeleteVertexArrays(vertexArray);
        vertexBuffer = 0;
        vertexArray = 0;
    }
}
