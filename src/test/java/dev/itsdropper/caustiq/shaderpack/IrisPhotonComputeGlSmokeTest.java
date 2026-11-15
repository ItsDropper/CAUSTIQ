package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IrisPhotonComputeGlSmokeTest {
    @Test
    void executesPinnedPhotonLpvComputeProgramsOnHeadlessOpenGl() throws IOException {
        Assumptions.assumeTrue("1".equals(System.getenv("CAUSTIQ_GL_SMOKE")),
                "CI runs this test under Xvfb with Mesa software OpenGL.");

        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        assertNotNull(photonDirectory, "Pinned Photon source checkout must be available in CI.");

        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        assertTrue(GLFW.glfwInit(), "GLFW could not initialize the Xvfb display.");
        long window = 0L;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(32, 32, "CAUSTIQ compute CI", 0L, 0L);
            assertNotEquals(0L, window, "Mesa did not provide an OpenGL 4.3 context.");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String version = GL11C.glGetString(GL11C.GL_VERSION);
            assertNotNull(version);
            assertTrue(GL.getCapabilities().OpenGL43, "Compute shaders require OpenGL 4.3; driver reports " + version);

            ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                    Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                    ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
            Map<String, String> defines = Map.of(
                    "COLORED_LIGHTS", "1", "VOXEL_VOLUME_SIZE", "64",
                    "MC_VERSION", "12111", "MC_HAND_DEPTH", "0.125");
            Set<String> compilerDefines = Set.of(
                    "COLORED_LIGHTS=1", "VOXEL_VOLUME_SIZE=64",
                    "MC_VERSION=12111", "MC_HAND_DEPTH=0.125");

            try (ShaderPackSources sources = ShaderPackSources.open(descriptor);
                 IrisImageResources images = IrisImageResources.load(
                         sources.read("shaders.properties"), defines)) {
                IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
                IrisShaderPackModel.ProgramSet world = model.dimensionOverrides().get("world0");
                assertNotNull(world, "Photon must expose its overworld program set.");
                IrisGlProgramCompiler compiler = new IrisGlProgramCompiler();

                IrisGlProgram setup = compiler.compile(catalogProgram(
                        world.programs().get("setup"), ShaderPackProgramCatalog.Group.SETUP),
                        sources::read, compilerDefines);
                IrisGlProgram shadowcomp = null;
                try {
                    shadowcomp = compiler.compile(catalogProgram(
                            world.programs().get("shadowcomp"), ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE),
                            sources::read, compilerDefines);

                    assertTrue(setup.compute());
                    assertTrue(shadowcomp.compute());
                    assertEquals(Set.of("light_data_img"), setup.images());
                    assertEquals(Set.of("light_img_a", "light_img_b"), shadowcomp.images());
                    assertTrue(shadowcomp.samplers().containsAll(Set.of(
                            "light_sampler_a", "light_sampler_b", "voxel_sampler", "light_data_sampler")));

                    GL20C.glUseProgram(setup.programId());
                    images.bindImages(setup.programId(), setup.images());
                    int[] setupGroups = images.dispatchGroups(setup.images(),
                            setup.localSizeX(), setup.localSizeY(), setup.localSizeZ(),
                            setup.usesGlobalY(), setup.usesGlobalZ());
                    assertArrayEquals(new int[]{1, 1, 1}, setupGroups);
                    GL43C.glDispatchCompute(setupGroups[0], setupGroups[1], setupGroups[2]);
                    GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
                    images.restoreImages();

                    // Seed one known emitter after setup has populated its light-color lookup.
                    GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, images.textureId("voxel_img"));
                    ByteBuffer emitter = BufferUtils.createByteBuffer(1);
                    emitter.put((byte) 32).flip();
                    GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0,
                            1, 1, 1, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_BYTE, emitter);
                    GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT
                            | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);

                    GL20C.glUseProgram(shadowcomp.programId());
                    int unit = 0;
                    for (String sampler : shadowcomp.samplers()) {
                        int location = GL20C.glGetUniformLocation(shadowcomp.programId(), sampler);
                        assertTrue(location >= 0, "Photon compute sampler was optimized out unexpectedly: " + sampler);
                        assertTrue(images.bindSampler(sampler, unit++, location),
                                "Photon sampler alias is not connected to an allocated image: " + sampler);
                    }
                    uploadMatrix(shadowcomp.programId(), "gbufferModelViewInverse");
                    uploadMatrix(shadowcomp.programId(), "gbufferPreviousModelView");
                    uploadVector(shadowcomp.programId(), "cameraPosition");
                    uploadVector(shadowcomp.programId(), "previousCameraPosition");
                    int frameCounter = GL20C.glGetUniformLocation(shadowcomp.programId(), "frameCounter");
                    assertTrue(frameCounter >= 0);
                    GL20C.glUniform1i(frameCounter, 0);

                    images.bindImages(shadowcomp.programId(), shadowcomp.images());
                    int[] shadowGroups = images.dispatchGroups(shadowcomp.images(),
                            shadowcomp.localSizeX(), shadowcomp.localSizeY(), shadowcomp.localSizeZ(),
                            shadowcomp.usesGlobalY(), shadowcomp.usesGlobalZ());
                    assertArrayEquals(new int[]{2, 64, 64}, shadowGroups);
                    GL43C.glDispatchCompute(shadowGroups[0], shadowGroups[1], shadowGroups[2]);
                    GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
                    images.restoreImages();

                    FloatBuffer output = BufferUtils.createFloatBuffer(64 * 64 * 64 * 4);
                    GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, images.textureId("light_img_a"));
                    GL11C.glGetTexImage(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, output);
                    assertTrue(Float.isFinite(output.get(0)) && output.get(0) > 0.1f,
                            "Photon shadowcomp did not propagate the seeded emitter into light_img_a.");
                    assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError(),
                            "OpenGL reported an error during Photon LPV compute execution.");
                } finally {
                    setup.close();
                    if (shadowcomp != null) shadowcomp.close();
                    GL20C.glUseProgram(0);
                }
            }
        } finally {
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errorCallback.free();
        }
    }

    private static ShaderPackProgramCatalog.Program catalogProgram(
            IrisShaderPackModel.Program program, ShaderPackProgramCatalog.Group group) {
        assertNotNull(program);
        return new ShaderPackProgramCatalog.Program(program.name(), group, 0, program.stages());
    }

    private static void uploadMatrix(int program, String name) {
        int location = GL20C.glGetUniformLocation(program, name);
        assertTrue(location >= 0, "Photon matrix uniform is missing: " + name);
        GL20C.glUniformMatrix4fv(location, false, new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        });
    }

    private static void uploadVector(int program, String name) {
        int location = GL20C.glGetUniformLocation(program, name);
        assertTrue(location >= 0, "Photon vector uniform is missing: " + name);
        GL20C.glUniform3f(location, 0.0f, 0.0f, 0.0f);
    }
}
