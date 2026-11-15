package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisImageResourcesTest {
    @Test
    void selectsConditionalPhotonLpvImageSizesFromShaderOptions() throws IOException {
        String properties = """
                #if (!defined IRIS_VULKAN && defined MC_GL_VENDOR_INTEL && defined MC_OS_WINDOWS)
                unrelated.platform.setting = true
                #endif
                #ifdef COLORED_LIGHTS
                image.light_data_img = light_data_sampler rgba rgba16f half_float false false 48 2
                #if VOXEL_VOLUME_SIZE == 64
                image.voxel_img = voxel_sampler red_integer r8ui unsigned_byte true false 64 64 64
                image.light_img_a = light_sampler_a rgba rgba16f half_float false false 64 64 64
                image.light_img_b = light_sampler_b rgba rgba16f half_float false false 64 64 64
                #elif VOXEL_VOLUME_SIZE == 128
                image.voxel_img = voxel_sampler red_integer r8ui unsigned_byte true false 128 128 128
                image.light_img_a = light_sampler_a rgba rgba16f half_float false false 128 128 128
                image.light_img_b = light_sampler_b rgba rgba16f half_float false false 128 128 128
                #else
                image.voxel_img = voxel_sampler red_integer r8ui unsigned_byte true false 256 256 256
                image.light_img_a = light_sampler_a rgba rgba16f half_float false false 256 256 256
                image.light_img_b = light_sampler_b rgba rgba16f half_float false false 256 256 256
                #endif
                #endif
                """;

        Map<String, IrisImageResources.ImageSpec> images = IrisImageResources.parse(properties,
                Map.of("COLORED_LIGHTS", "1", "VOXEL_VOLUME_SIZE", "128"));

        assertEquals(4, images.size());
        assertEquals(48, images.get("light_data_img").width());
        assertEquals(2, images.get("light_data_img").height());
        assertEquals(128, images.get("voxel_img").width());
        assertEquals(128, images.get("voxel_img").height());
        assertEquals(128, images.get("voxel_img").depth());
        assertEquals("voxel_sampler", images.get("voxel_img").sampler());
        assertEquals(128, images.get("light_img_a").depth());
        assertEquals(128, images.get("light_img_b").depth());
    }

    @Test
    void resolvesPinnedPhotonLpvComputeProgramsAndTheir3dImages() throws IOException {
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "Pinned Photon checkout is supplied by CI");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        Map<String, String> defines = Map.of("COLORED_LIGHTS", "1", "VOXEL_VOLUME_SIZE", "128");
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(
                    model, "minecraft:overworld", defines);
            assertTrue(plan.orderedProgramNames().contains("setup"), plan.orderedProgramNames().toString());
            assertTrue(plan.orderedProgramNames().contains("shadowcomp"), plan.orderedProgramNames().toString());
            assertTrue(plan.passes().stream().filter(pass ->
                    pass.pass().name().equals("setup") || pass.pass().name().equals("shadowcomp"))
                    .allMatch(pass -> pass.pass().compute()));

            Map<String, IrisImageResources.ImageSpec> images =
                    IrisImageResources.parse(sources.read("shaders.properties"), defines);
            assertEquals(4, images.size(), images.keySet().toString());
            assertEquals(48, images.get("light_data_img").width());
            assertEquals(2, images.get("light_data_img").height());
            assertEquals(128, images.get("voxel_img").depth());
            assertEquals(128, images.get("light_img_a").depth());
            assertEquals(128, images.get("light_img_b").depth());

            GlslPreprocessor preprocessor = new GlslPreprocessor();
            String setup = preprocessor.preprocess("world0/setup.csh", sources::read).source();
            String shadowcomp = preprocessor.preprocess("world0/shadowcomp.csh", sources::read).source();
            assertTrue(setup.contains("light_data_img"));
            assertTrue(shadowcomp.contains("voxel_sampler"));
            assertTrue(shadowcomp.contains("light_img_a"));
            assertTrue(shadowcomp.contains("gl_GlobalInvocationID"));
        }
    }

    @Test
    void doesNotAllocateImagesWhenColoredLightsAreDisabled() throws IOException {
        String properties = """
                #ifdef COLORED_LIGHTS
                image.voxel_img = voxel_sampler red_integer r8ui unsigned_byte true false 128 128 128
                #endif
                """;

        assertTrue(IrisImageResources.parse(properties, Map.of("COLORED_LIGHTS", "false")).isEmpty());
    }
}
