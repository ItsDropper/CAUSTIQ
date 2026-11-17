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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PhotonTerrainScreenshotTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;

    @Test
    void rendersPinnedPhotonTerrainStagesAndWritesScreenshot() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("CAUSTIQ_GL_SMOKE")),
                "Run under Xvfb with Mesa software OpenGL.");
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        assertNotNull(photonDirectory, "Pinned Photon source checkout must be available.");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                    model, "world0", "gbuffers_terrain",
                    Map.of("NORMAL_MAPPING", "true", "SPECULAR_MAPPING", "true", "TAA", "true"));
            Map<String, String> harness = translator.softwareRenderHarnessSources();

            GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err);
            callback.set();
            assertTrue(GLFW.glfwInit(), "GLFW could not initialize Xvfb.");
            long window = 0L;
            int program = 0, framebuffer = 0, whiteTexture = 0, vertexArray = 0;
            int[] textures = new int[2];
            try {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "CAUSTIQ Photon screenshot CI", 0L, 0L);
                assertNotEquals(0L, window, "Mesa did not provide an OpenGL 4.3 context.");
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                assertTrue(GL.getCapabilities().OpenGL43, "OpenGL 4.3 required; driver: "
                        + GL11C.glGetString(GL11C.GL_VERSION));

                int vertex = compile(GL20C.GL_VERTEX_SHADER, harness.get("VERTEX"), "Photon translated vertex");
                int fragment = compile(GL20C.GL_FRAGMENT_SHADER, harness.get("FRAGMENT"), "Photon translated fragment");
                program = GL20C.glCreateProgram();
                GL20C.glAttachShader(program, vertex);
                GL20C.glAttachShader(program, fragment);
                GL20C.glLinkProgram(program);
                String linkLog = GL20C.glGetProgramInfoLog(program);
                GL20C.glDeleteShader(vertex);
                GL20C.glDeleteShader(fragment);
                assertEquals(GL20C.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS),
                        "Translated Photon terrain program failed to link: " + linkLog);

                framebuffer = GL30C.glGenFramebuffers();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL11C.glGenTextures(textures);
                for (int i = 0; i < textures.length; i++) {
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA8, WIDTH, HEIGHT, 0,
                            GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                    GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i,
                            GL11C.GL_TEXTURE_2D, textures[i], 0);
                }
                GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
                assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));

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
                uploadUniforms(program);
                vertexArray = GL30C.glGenVertexArrays();
                GL30C.glBindVertexArray(vertexArray);
                GL11C.glViewport(0, 0, WIDTH, HEIGHT);
                GL11C.glClearColor(0.035f, 0.055f, 0.09f, 1.0f);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
                assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError(),
                        "OpenGL error while executing translated Photon terrain stages.");

                GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT1);
                ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
                GL11C.glReadPixels(0, 0, WIDTH, HEIGHT, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
                BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
                boolean renderedPixel = false;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int offset = (y * WIDTH + x) * 4;
                        int r = Byte.toUnsignedInt(pixels.get(offset));
                        int g = Byte.toUnsignedInt(pixels.get(offset + 1));
                        int b = Byte.toUnsignedInt(pixels.get(offset + 2));
                        int a = Byte.toUnsignedInt(pixels.get(offset + 3));
                        image.setRGB(x, HEIGHT - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
                        renderedPixel |= r != 9 || g != 14 || b != 23;
                    }
                }
                assertTrue(renderedPixel, "Photon terrain render produced only the framebuffer clear color.");
                Path output = Path.of(System.getenv().getOrDefault(
                        "CAUSTIQ_SCREENSHOT_DIR", "build/caustiq-photon-screenshots"));
                Files.createDirectories(output);
                Path screenshot = output.resolve("photon-terrain-mesa.png");
                assertTrue(ImageIO.write(image, "png", screenshot.toFile()), "PNG writer unavailable.");
                assertTrue(Files.size(screenshot) > 100, "Screenshot output is unexpectedly empty.");
                System.out.println("CAUSTIQ_SCREENSHOT=" + screenshot.toAbsolutePath());
                System.out.println("CAUSTIQ_GL_RENDERER=" + GL11C.glGetString(GL11C.GL_RENDERER));
            } finally {
                if (program != 0) GL20C.glDeleteProgram(program);
                if (vertexArray != 0) GL30C.glDeleteVertexArrays(vertexArray);
                if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
                for (int texture : textures) if (texture != 0) GL11C.glDeleteTextures(texture);
                if (whiteTexture != 0) GL11C.glDeleteTextures(whiteTexture);
                if (window != 0L) GLFW.glfwDestroyWindow(window);
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
                callback.free();
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
            fail(label + " failed to compile: " + log);
        }
        return shader;
    }

    private static void uploadUniforms(int program) {
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        for (String name : new String[]{"caustiqProjection", "caustiqModelView", "caustiqProjectionInverse",
                "caustiqModelViewInverse", "caustiqShadowModelView", "caustiqShadowModelViewInverse",
                "caustiqShadowProjection", "caustiqShadowProjectionInverse"}) {
            int location = GL20C.glGetUniformLocation(program, name);
            if (location >= 0) GL20C.glUniformMatrix4fv(location, false, identity);
        }
        int normal = GL20C.glGetUniformLocation(program, "caustiqNormalMatrix");
        if (normal >= 0) GL20C.glUniformMatrix3fv(normal, false, new float[]{1,0,0, 0,1,0, 0,0,1});
        vec3(program, "caustiqCameraPosition", 0, 0, 0);
        vec3(program, "caustiqLightDirection", 0, 1, 0);
        vec4(program, "caustiqEntityColor", 0, 0, 0, 0);
        vec2(program, "screenSize", WIDTH, HEIGHT);
        vec2(program, "texelSize", 1f / WIDTH, 1f / HEIGHT);
        vec2(program, "texCoordShrink", 0.0001f, 0.0001f);
        vec2(program, "environmentFog", 0, 0);
        vec2(program, "renderFog", 0, 0);
        vec4(program, "fogColour", 0, 0, 0, 0);
        vec2(program, "caustiqTaaOffset", 0, 0);
        sampler(program, "caustiqNormalTexture", 4);
        sampler(program, "caustiqSpecularTexture", 5);
        scalar(program, "caustiqAspectRatio", 1);
        scalar(program, "caustiqNear", 0.1f);
        scalar(program, "caustiqFar", 1000);
        scalar(program, "caustiqAlphaTestRef", 0.1f);
        scalar(program, "caustiqBiomeTemperature", 0.5f);
        scalar(program, "caustiqBiomeHumidity", 0.5f);
        scalar(program, "caustiqFrameTimeCounter", 1);
        scalar(program, "caustiqRainStrength", 0);
        scalar(program, "caustiqWetness", 0);
        scalar(program, "caustiqWorldAge", 0);
        scalar(program, "caustiqDayFactor", 0);
        for (String name : new String[]{"caustiqFrameCounter", "caustiqWorldTime", "caustiqWorldDay",
                "caustiqBlockEntityId", "caustiqEntityId", "caustiqCurrentRenderedItemId",
                "caustiqIsEyeInWater", "caustiqMoonPhase", "caustiqHeldBlockLightValue",
                "caustiqHeldBlockLightValue2", "caustiqHeldItemId", "caustiqHeldItemId2",
                "caustiqHeavyFog", "caustiqIsElytraFlying"}) {
            int location = GL20C.glGetUniformLocation(program, name);
            if (location >= 0) GL20C.glUniform1i(location, 0);
        }
    }

    private static void sampler(int p, String n, int unit) {
        int location = GL20C.glGetUniformLocation(p, n);
        if (location >= 0) GL20C.glUniform1i(location, unit);
    }
    private static void scalar(int p, String n, float v) {
        int l = GL20C.glGetUniformLocation(p, n); if (l >= 0) GL20C.glUniform1f(l, v);
    }
    private static void vec2(int p, String n, float x, float y) {
        int l = GL20C.glGetUniformLocation(p, n); if (l >= 0) GL20C.glUniform2f(l, x, y);
    }
    private static void vec3(int p, String n, float x, float y, float z) {
        int l = GL20C.glGetUniformLocation(p, n); if (l >= 0) GL20C.glUniform3f(l, x, y, z);
    }
    private static void vec4(int p, String n, float x, float y, float z, float w) {
        int l = GL20C.glGetUniformLocation(p, n); if (l >= 0) GL20C.glUniform4f(l, x, y, z, w);
    }
}
