package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.shaderpack.IrisShaderPackModel;
import dev.itsdropper.caustiq.shaderpack.IrisShaderPackParser;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackLayout;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumIrisTerrainGlRenderSmokeTest {
    @Test
    void rendersTheTranslatedPhotonTerrainVertexAndFragmentProgramsOnMesa() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("CAUSTIQ_GL_SMOKE")),
                "CI runs the software render test under Xvfb with Mesa llvmpipe.");

        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        assertNotNull(photonDirectory, "Pinned Photon source checkout must be available in CI.");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                    model, "world0", "gbuffers_terrain",
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false", "TAA", "false"));
            Map<String, String> harness = translator.softwareRenderHarnessSources();

            GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
            errorCallback.set();
            assertTrue(GLFW.glfwInit(), "GLFW could not initialize the Xvfb display.");
            long window = 0L;
            int program = 0;
            int framebuffer = 0;
            int[] textures = new int[2];
            int whiteTexture = 0;
            int vertexArray = 0;
            try {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                window = GLFW.glfwCreateWindow(16, 16, "CAUSTIQ terrain render CI", 0L, 0L);
                assertNotEquals(0L, window, "Mesa did not provide an OpenGL 4.3 context.");
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                assertTrue(GL.getCapabilities().OpenGL43,
                        "Terrain render harness requires OpenGL 4.3; driver reports "
                                + GL11C.glGetString(GL11C.GL_VERSION));

                int vertex = compile(GL20C.GL_VERTEX_SHADER, harness.get("VERTEX"), "Photon translated vertex stage");
                int fragment = compile(GL20C.GL_FRAGMENT_SHADER, harness.get("FRAGMENT"), "Photon translated fragment stage");
                program = GL20C.glCreateProgram();
                GL20C.glAttachShader(program, vertex);
                GL20C.glAttachShader(program, fragment);
                GL20C.glLinkProgram(program);
                String linkLog = GL20C.glGetProgramInfoLog(program);
                assertEquals(GL20C.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS),
                        "Translated Photon terrain program did not link: " + linkLog);
                GL20C.glDeleteShader(vertex);
                GL20C.glDeleteShader(fragment);

                framebuffer = GL30C.glGenFramebuffers();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL11C.glGenTextures(textures);
                for (int index = 0; index < textures.length; index++) {
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[index]);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, 16, 16, 0,
                            GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                    GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,
                            GL30C.GL_COLOR_ATTACHMENT0 + index, GL11C.GL_TEXTURE_2D, textures[index], 0);
                }
                GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
                assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER),
                        "Mesa could not create the terrain MRT framebuffer.");

                whiteTexture = GL11C.glGenTextures();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, whiteTexture);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                ByteBuffer white = BufferUtils.createByteBuffer(4).put(new byte[]{-1, -1, -1, -1});
                white.flip();
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, 1, 1, 0,
                        GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, white);
                for (int unit = 0; unit <= 10; unit++) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, whiteTexture);
                }

                GL20C.glUseProgram(program);
                uploadTestUniforms(program);
                vertexArray = GL30C.glGenVertexArrays();
                GL30C.glBindVertexArray(vertexArray);
                GL11C.glViewport(0, 0, 16, 16);
                GL11C.glClearColor(0, 0, 0, 0);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
                assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError(),
                        "OpenGL reported an error while executing the translated Photon terrain stages.");

                GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT1);
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                GL11C.glReadPixels(8, 8, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
                int red = Byte.toUnsignedInt(pixel.get(0));
                int green = Byte.toUnsignedInt(pixel.get(1));
                int blue = Byte.toUnsignedInt(pixel.get(2));
                int alpha = Byte.toUnsignedInt(pixel.get(3));
                assertTrue(red != 0 || green != 0 || blue != 0 || alpha != 0,
                        "Translated Photon terrain stages linked but produced no framebuffer output.");
            } finally {
                if (program != 0) GL20C.glDeleteProgram(program);
                if (vertexArray != 0) GL30C.glDeleteVertexArrays(vertexArray);
                if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
                for (int texture : textures) if (texture != 0) GL11C.glDeleteTextures(texture);
                if (whiteTexture != 0) GL11C.glDeleteTextures(whiteTexture);
                if (window != 0L) GLFW.glfwDestroyWindow(window);
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
                errorCallback.free();
            }
        }
    }

    private static int compile(int type, String source, String label) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        String log = GL20C.glGetShaderInfoLog(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL20C.GL_TRUE) {
            GL20C.glDeleteShader(shader);
            fail(label + " failed to compile: " + log + "\n" + numberedSource(source));
        }
        return shader;
    }

    private static String numberedSource(String source) {
        String[] lines = source.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            result.append(index + 1).append(": ").append(lines[index]).append('\n');
        }
        return result.toString();
    }

    private static void uploadTestUniforms(int program) {
        float[] identity4 = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
        for (String name : new String[]{
                "caustiqProjection", "caustiqModelView", "caustiqProjectionInverse",
                "caustiqModelViewInverse", "caustiqShadowModelView", "caustiqShadowModelViewInverse",
                "caustiqShadowProjection", "caustiqShadowProjectionInverse"}) {
            int location = GL20C.glGetUniformLocation(program, name);
            if (location >= 0) GL20C.glUniformMatrix4fv(location, false, identity4);
        }
        int normalMatrix = GL20C.glGetUniformLocation(program, "caustiqNormalMatrix");
        if (normalMatrix >= 0) {
            GL20C.glUniformMatrix3fv(normalMatrix, false, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
        }
        uniform3(program, "caustiqCameraPosition", 0, 0, 0);
        uniform3(program, "caustiqLightDirection", 0, 1, 0);
        uniform4(program, "caustiqEntityColor", 0, 0, 0, 0);
        uniform2(program, "screenSize", 16, 16);
        uniform2(program, "texelSize", 1f / 16f, 1f / 16f);
        uniform2(program, "texCoordShrink", 0.0001f, 0.0001f);
        uniform2(program, "environmentFog", 0, 0);
        uniform2(program, "renderFog", 0, 0);
        uniform4(program, "fogColour", 0, 0, 0, 0);
        uniform2(program, "caustiqTaaOffset", 0, 0);
        uniform2i(program, "caustiqAtlasSize", 16, 16);
        uniform1f(program, "caustiqAspectRatio", 1);
        uniform1f(program, "caustiqNear", 0.1f);
        uniform1f(program, "caustiqFar", 1000);
        uniform1f(program, "caustiqAlphaTestRef", 0.1f);
        uniform1f(program, "caustiqBiomeTemperature", 0.5f);
        uniform1f(program, "caustiqBiomeHumidity", 0.5f);
        uniform1f(program, "caustiqFrameTimeCounter", 1);
        uniform1f(program, "caustiqRainStrength", 0);
        uniform1f(program, "caustiqWetness", 0);
        uniform1f(program, "caustiqWorldAge", 0);
        uniform1f(program, "caustiqDayFactor", 0);
        uniform1f(program, "caustiqTimeSunrise", 0);
        uniform1f(program, "caustiqTimeNoon", 0);
        uniform1f(program, "caustiqTimeSunset", 0);
        uniform1f(program, "caustiqTimeMidnight", 0);
        uniform1i(program, "caustiqFrameCounter", 0);
        uniform1i(program, "caustiqWorldTime", 0);
        uniform1i(program, "caustiqWorldDay", 0);
        uniform1i(program, "caustiqBlockEntityId", 0);
        uniform1i(program, "caustiqEntityId", 0);
        uniform1i(program, "caustiqCurrentRenderedItemId", 0);
        uniform1i(program, "caustiqIsEyeInWater", 0);
        uniform1i(program, "caustiqMoonPhase", 0);
        uniform1i(program, "caustiqHeldBlockLightValue", 0);
        uniform1i(program, "caustiqHeldBlockLightValue2", 0);
        uniform1i(program, "caustiqHeldItemId", 0);
        uniform1i(program, "caustiqHeldItemId2", 0);
        uniform1i(program, "caustiqHeavyFog", 0);
        uniform1i(program, "caustiqIsElytraFlying", 0);
    }

    private static void uniform1f(int program, String name, float value) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1f(location, value);
    }

    private static void uniform1i(int program, String name, int value) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform1i(location, value);
    }

    private static void uniform2(int program, String name, float x, float y) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform2f(location, x, y);
    }

    private static void uniform2i(int program, String name, int x, int y) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform2i(location, x, y);
    }

    private static void uniform3(int program, String name, float x, float y, float z) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform3f(location, x, y, z);
    }

    private static void uniform4(int program, String name, float x, float y, float z, float w) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) GL20C.glUniform4f(location, x, y, z, w);
    }
}
