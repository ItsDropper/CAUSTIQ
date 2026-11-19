package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumDepthSnapshotGlTest {
    @Test
    void copiesTheEntireDepthBufferWithScissorEnabledAndRestoresFramebufferState() {
        Assumptions.assumeTrue("1".equals(System.getenv("CAUSTIQ_GL_SMOKE")),
                "Run under Xvfb with Mesa software OpenGL.");

        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err);
        callback.set();
        assertTrue(GLFW.glfwInit(), "GLFW could not initialize Xvfb.");
        long window = 0L;
        int sourceFramebuffer = 0;
        int destinationFramebuffer = 0;
        int sourceTexture = 0;
        int destinationTexture = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(8, 8, "CAUSTIQ depth snapshot test", 0L, 0L);
            assertNotEquals(0L, window, "Mesa did not provide an OpenGL 4.3 context.");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            sourceFramebuffer = createDepthFramebuffer(GL30C.GL_DEPTH_COMPONENT24);
            sourceTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            destinationFramebuffer = createDepthFramebuffer(GL30C.GL_DEPTH_COMPONENT24);
            destinationTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);

            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sourceFramebuffer);
            GL11C.glClearDepth(0.25);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
            GL11C.glClearDepth(1.0);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            assertEquals(GL30C.GL_DEPTH_COMPONENT24,
                    NvidiumIrisTextureBridge.depthAttachmentInternalFormat(sourceFramebuffer),
                    "Depth snapshot must detect the source attachment's exact internal format.");
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL11C.glScissor(0, 0, 1, 1);

            NvidiumIrisTextureBridge.blitDepthSnapshot(
                    sourceFramebuffer, destinationFramebuffer, new int[]{0, 0, 8, 8});

            assertTrue(GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                    "Depth snapshot must restore the caller's scissor enable state.");
            assertEquals(0, GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    "Depth snapshot must restore the read framebuffer binding.");
            assertEquals(0, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    "Depth snapshot must restore the draw framebuffer binding.");

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, destinationFramebuffer);
            FloatBuffer depth = BufferUtils.createFloatBuffer(8 * 8);
            GL11C.glReadPixels(0, 0, 8, 8, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
            for (int index = 0; index < depth.capacity(); index++) {
                assertEquals(0.25f, depth.get(index), 0.0001f,
                        "Depth snapshot pixel " + index + " was not copied; scissor may have clipped the blit.");
            }
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError(),
                    "OpenGL reported an error while copying the depth attachment.");
        } finally {
            if (window != 0L) {
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            }
            if (sourceFramebuffer != 0) GL30C.glDeleteFramebuffers(sourceFramebuffer);
            if (destinationFramebuffer != 0) GL30C.glDeleteFramebuffers(destinationFramebuffer);
            if (sourceTexture != 0) GL11C.glDeleteTextures(sourceTexture);
            if (destinationTexture != 0) GL11C.glDeleteTextures(destinationTexture);
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            callback.free();
        }
    }

    private static int createDepthFramebuffer() {
        return createDepthFramebuffer(GL30C.GL_DEPTH_COMPONENT32F);
    }

    private static int createDepthFramebuffer(int internalFormat) {
        int framebuffer = GL30C.glGenFramebuffers();
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat,
                8, 8, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, texture, 0);
        GL11C.glDrawBuffer(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER),
                "Mesa could not create a depth-only framebuffer.");
        return framebuffer;
    }
}
