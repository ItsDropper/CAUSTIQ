package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisShaderResourcesTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsCustomPngTexturesAndScalarAndVectorUniformDeclarations() throws IOException {
        Path textureDirectory = Files.createDirectories(temporaryDirectory.resolve("Pack/shaders/textures"));
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0xff00ff00);
        image.setRGB(0, 1, 0xff0000ff);
        image.setRGB(1, 1, 0xffffffff);
        ImageIO.write(image, "png", textureDirectory.resolve("lookup.png").toFile());
        Files.writeString(textureDirectory.getParent().resolve("shaders.properties"), """
                texture.lookupSampler=textures/lookup.png
                variable.float.baseBrightness=rainStrength * 2.0
                variable.vec2.offset=vec2(0.25, 0.75)
                uniform.float.customBrightness=clamp(baseBrightness, 0.0, 1.0)
                uniform.vec2.customOffset=offset
                """);

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("resource-pack", "ResourcePack",
                temporaryDirectory.resolve("Pack"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertTrue(model.diagnostics().isEmpty(), model.diagnostics().toString());
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                assertEquals(Set.of("lookupSampler"), resources.textureNames());
                assertEquals(Set.of("customBrightness", "customOffset"), resources.uniformNames());
                assertEquals(Set.of("baseBrightness", "offset"), resources.variableNames());
                Map<String, double[]> values = resources.evaluateUniformValues(Map.of("rainStrength", 0.25));
                assertEquals(0.5, values.get("customBrightness")[0], 1.0e-9);
                assertArrayEquals(new double[]{0.25, 0.75}, values.get("customOffset"), 1.0e-9);
            }
        }
    }

    @Test
    void resolvesComponentsOfCustomVectorVariablesAndTheirSwizzles() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Vector/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                variable.vec3.sunPosition=vec3(0.25, 0.5, 0.75)
                variable.vec4.sunColor=vec4(sunPosition.x, sunPosition.y, sunPosition.z, 1.0)
                uniform.float.sunRed=sunPosition.r
                uniform.float.sunGreen=sunPosition.g
                uniform.float.sunBlue=sunPosition.b
                uniform.float.sunAlpha=sunColor.a
                uniform.vec4.resolvedSunColor=sunColor
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("vector", "Vector",
                temporaryDirectory.resolve("Vector"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, double[]> values = resources.evaluateUniformValues(Map.of());
                assertEquals(0.25, values.get("sunRed")[0], 1.0e-9);
                assertEquals(0.5, values.get("sunGreen")[0], 1.0e-9);
                assertEquals(0.75, values.get("sunBlue")[0], 1.0e-9);
                assertEquals(1.0, values.get("sunAlpha")[0], 1.0e-9);
                assertArrayEquals(new double[]{0.25, 0.5, 0.75, 1.0},
                        values.get("resolvedSunColor"), 1.0e-9);
            }
        }
    }

    @Test
    void resolvesComponentsAndAliasesOfCustomVectorUniforms() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("UniformVectors/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.vec3.sun_dir=vec3(0.25, -0.5, 0.75)
                variable.float.day_factor_u=clamp(abs(sun_dir.y) / 0.3, 0.0, 1.0)
                uniform.float.day_factor=day_factor_u * day_factor_u
                uniform.float.sun_vertical=abs(sun_dir.y)
                uniform.vec3.sun_dir_copy=sun_dir
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("uniform-vectors", "UniformVectors",
                temporaryDirectory.resolve("UniformVectors"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, double[]> values = resources.evaluateUniformValues(Map.of());
                assertEquals(1.0, values.get("day_factor")[0], 1.0e-9);
                assertEquals(0.5, values.get("sun_vertical")[0], 1.0e-9);
                assertArrayEquals(new double[]{0.25, -0.5, 0.75}, values.get("sun_dir_copy"), 1.0e-9);
            }
        }
    }


    @Test
    void evaluatesSelectedNumericAndBooleanOptionDefinesInCustomUniformExpressions() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("OptionDefines/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                variable.float.rainfall_factor=rainfall * (IS_WET ? 1.0 : 0.0)
                uniform.float.rainfall_result=rainfall_factor + enabled_feature
                uniform.float.wet_result=IS_WET
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("option-defines", "OptionDefines",
                temporaryDirectory.resolve("OptionDefines"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources,
                    Map.of("rainfall", "0.75", "IS_WET", "true", "enabled_feature", "1"))) {
                Map<String, double[]> values = resources.evaluateUniformValues(Map.of());
                assertEquals(1.75, values.get("rainfall_result")[0], 1.0e-9);
                assertEquals(1.0, values.get("wet_result")[0], 1.0e-9);
            }
        }
    }

    @Test
    void resolvesScalarCustomUniformDependenciesInDeclarationOrder() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ScalarUniforms/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.me_fade=if(sun_dir_y < 0.18, 0.37 + 1.2 * max(0.0, -sun_dir_y), 1.7)
                uniform.float.me_weight=pow(clamp(1.0 - me_fade * abs(sun_dir_y - 0.18), 0.0, 1.0), 2.0)
                uniform.float.time_sunrise=if(sun_dir_x > 0.0, 1.0, 0.0) * me_weight
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("scalar-uniforms", "ScalarUniforms",
                temporaryDirectory.resolve("ScalarUniforms"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                Map<String, double[]> values = resources.evaluateUniformValues(
                        Map.of("sun_dir_y", 0.5, "sun_dir_x", 1.0));
                assertEquals(0.207936, values.get("me_weight")[0], 1.0e-9);
                assertEquals(0.207936, values.get("time_sunrise")[0], 1.0e-9);
            }
        }
    }

    @Test
    void rejectsCyclicScalarCustomUniformDependencies() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("CyclicUniforms/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                uniform.float.first=second + 1.0
                uniform.float.second=first + 1.0
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("cyclic-uniforms", "CyclicUniforms",
                temporaryDirectory.resolve("CyclicUniforms"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                        () -> resources.evaluateUniformValues(Map.of()));
                assertTrue(exception.getMessage().contains("Cyclic Iris uniform dependency"));
            }
        }
    }

    @Test
    void loadsProgramScopedRawThreeDimensionalTextures() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Scoped/shaders"));
        Path imageDirectory = Files.createDirectories(shaders.resolve("image"));
        Files.write(imageDirectory.resolve("volume.dat"), new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        Files.writeString(shaders.resolve("shaders.properties"), """
                texture.deferred.colortex6.1=image/volume.dat TEXTURE_3D R8 2 2 2 RED UNSIGNED_BYTE
                """);
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("scoped", "Scoped",
                temporaryDirectory.resolve("Scoped"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            try (IrisShaderResources resources = IrisShaderResources.load(model.properties(), sources)) {
                assertTrue(resources.textureNames().contains("deferred::colortex6"));
                assertFalse(resources.hasTexture("colortex6"),
                        "Program-scoped textures must not leak into unrelated passes.");
            }
        }
    }

    @Test
    void rejectsCustomTexturePathTraversal() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Unsafe/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "texture.bad=../outside.png\n");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("unsafe", "Unsafe",
                temporaryDirectory.resolve("Unsafe"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertThrows(IOException.class, () -> IrisShaderResources.load(model.properties(), sources));
        }
    }

    @Test
    void rejectsUnsupportedCustomUniformTypesInsteadOfSilentlyIgnoringThem() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Unsupported/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "uniform.mat4.customMatrix=mat4(1.0)\n");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("unsupported", "Unsupported",
                temporaryDirectory.resolve("Unsupported"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertThrows(IOException.class, () -> IrisShaderResources.load(model.properties(), sources));
        }
    }
}
