package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.shaderpack.IrisShaderPackModel;
import dev.itsdropper.caustiq.shaderpack.IrisShaderPackParser;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackLayout;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import dev.itsdropper.caustiq.shaderpack.ShaderPackProgramCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.nio.file.Files;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IrisNvidiumTerrainTranslatorTest {
    @Test
    void translatesVertexBuiltinsVaryingsAndFragmentSamplersIntoNvidiumStages() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                attribute vec3 at_midBlock;
                attribute vec3 iris_Normal;
                varying vec2 texCoord;
                uniform mat4 gbufferModelView;
                uniform mat4 gbufferProjection;
                void main() {
                    gl_Position = gbufferProjection * gbufferModelView * gl_Vertex;
                    gl_TexCoord[0] = gl_MultiTexCoord0;
                    vec2 rawLight = gl_MultiTexCoord1.xy;
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy + at_midBlock.xy + iris_Normal.xy + rawLight * 0.0;
                }
                """, """
                #version 150
                varying vec2 texCoord;
                uniform sampler2D gtexture;
                uniform vec2 viewSize;
                out vec4 fragColor;
                void main() {
                    gl_FragColor = texture2D(gtexture, texCoord);
                }
                """);

        Map<String, String> translated = translator.adapt(nativeStages());

        String mesh = translated.get("MESH");
        String fragment = translated.get("FRAGMENT");
        assertTrue(mesh.contains("void caustiqIrisVertexMain()"));
        assertTrue(mesh.contains("caustiqDecodeMidBlock(caustiqCurrentVertex)"));
        assertTrue(mesh.contains("caustiqDecodeNormal(caustiqCurrentVertex)"));
        assertTrue(mesh.contains("vec4(decodeLightUV(caustiqCurrentVertex) * 256.0, 0.0, 1.0)"));
        assertTrue(mesh.contains("mat4(1.0) * vec4(decodeVertexUV(caustiqCurrentVertex), 0.0, 1.0)"));
        assertFalse(mesh.contains("gl_TextureMatrix"));
        assertTrue(mesh.contains("caustiqProjection * caustiqModelView"));
        assertTrue(mesh.contains("gl_MeshVerticesNV[id].gl_Position = caustiqIrisClipPosition;"));
        assertTrue(mesh.contains("caustiqIrisClipPosition = gl_MeshVerticesNV[id].gl_Position;"));
        assertFalse(mesh.contains("caustiqIrisClipPosition = transformVertex(V);"));
        assertTrue(mesh.contains("OUT[id].texCoord = caustiqIrisVarying_texCoord;"));
        assertTrue(fragment.contains("void caustiqIrisFragmentMain()"));
        assertTrue(fragment.contains("texture(tex_diffuse, texCoord)"));
        assertTrue(fragment.contains("caustiqIrisTexCoord[0] = v_irisTexCoord0;"));
        assertFalse(fragment.contains("caustiqGBufferNormal"));
    }

    @Test
    void translatesEveryPinnedPhotonDimensionAgainstTheActualPinnedNvidiumShaderStages() throws Exception {
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "Photon source checkout is supplied by CI only");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            for (String dimension : List.of("world0", "world-1", "world1")) {
                IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                        model, dimension, "gbuffers_terrain",
                        Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false"));
                Map<String, String> translated = translator.adapt(
                        NvidiumTerrainVaryingAdapter.adapt(pinnedNvidiumStages()));
                assertTrue(translated.get("MESH").contains("void caustiqIrisVertexMain()"),
                        dimension + " vertex program was not translated");
                assertTrue(translated.get("FRAGMENT").contains("void caustiqIrisFragmentMain()"),
                        dimension + " fragment program was not translated");
                assertFalse(translator.renderTargetIndices().isEmpty(),
                        dimension + " has no routed fragment outputs");
                if (dimension.equals("world0")) {
                    assertEquals(List.of(1), translator.renderTargetIndices());
                }
            }

            IrisNvidiumTerrainTranslator defaults = IrisNvidiumTerrainTranslator.from(
                    model, "world0", "gbuffers_terrain",
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false"));
            assertEquals(List.of(1), defaults.renderTargetIndices());
        }
    }

    @Test
    void translatesGlFragDataToNvidiumMrtLocations() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.5);
                    gl_FragData[3] = vec4(0.25);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("layout(location = 1) out vec4 caustiqIrisOutput1;"));
        assertTrue(fragment.contains("layout(location = 3) out vec4 caustiqIrisOutput3;"));
        assertTrue(fragment.contains("caustiqIrisOutput1 = caustiqIrisFragData[1];"));
        assertTrue(fragment.contains("caustiqIrisOutput3 = caustiqIrisFragData[3];"));
    }

    @Test
    void rejectsUnavailableUniformsAndUnmatchedVaryingsBeforeShaderLink() {
        assertThrows(IllegalArgumentException.class, () -> translator("""
                #version 150
                uniform sampler2D shadowtex0;
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                void main() { gl_FragColor = vec4(1.0); }
                """));

        assertThrows(IllegalArgumentException.class, () -> translator("""
                #version 150
                varying vec2 texCoord;
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                varying vec3 texCoord;
                void main() { gl_FragColor = vec4(texCoord, 1.0); }
                """));
    }

    @Test
    void leavesNvidiumNonPrimaryPipelinesUntouched() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                void main() { gl_FragColor = vec4(1.0); }
                """);
        Map<String, String> stages = Map.of(
                "TASK", "task",
                "MESH", "#version 460\nlayout(triangles, max_vertices=128, max_primitives=64) out;\nvoid main() {}",
                "FRAGMENT", "#version 460\nlayout(location = 0) out vec4 colour;\nvoid main() {}");
        assertEquals(stages, translator.adapt(stages));
    }

    private static IrisNvidiumTerrainTranslator translator(String vertex, String fragment) {
        Map<ShaderPackProgramCatalog.Stage, String> stages = new LinkedHashMap<>();
        stages.put(ShaderPackProgramCatalog.Stage.VERTEX, "shaders/gbuffers_terrain.vsh");
        stages.put(ShaderPackProgramCatalog.Stage.FRAGMENT, "shaders/gbuffers_terrain.fsh");
        IrisShaderPackModel.Program program = new IrisShaderPackModel.Program("gbuffers_terrain", stages);
        IrisShaderPackModel model = new IrisShaderPackModel(
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), null,
                new IrisShaderPackModel.ProgramSet("", Map.of("gbuffers_terrain", program)),
                Map.of(), Map.of("shaders/gbuffers_terrain.vsh", vertex,
                        "shaders/gbuffers_terrain.fsh", fragment), List.of());
        return IrisNvidiumTerrainTranslator.from(model);
    }

    @Test
    void nativeVertexAbiAdapterAndIrisTranslatorComposeAgainstPinnedNvidiumSources() throws Exception {
        Map<String, String> nativeSources = pinnedNvidiumStages();
        assertTrue(nativeSources.get("MESH").contains("uint8_t section;"),
                "Pinned Nvidium mesh imports must be expanded before ABI adaptation");
        Map<String, String> extended = NvidiumTerrainVaryingAdapter.adapt(nativeSources);
        assertTrue(extended.get("MESH").contains("#define CAUSTIQ_NVIDIUM_TERRAIN_VARYINGS 4"));
        assertTrue(extended.get("MESH").contains("OUT[id].v_tbn = caustiqDecodeTbn(V);"));
        assertTrue(extended.get("FRAGMENT").contains("caustiqGBufferLightmap"));

        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "Photon source checkout is supplied by CI only");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                    model, "world0", "gbuffers_terrain",
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false"));
            Map<String, String> combined = translator.adapt(extended);
            assertTrue(combined.get("MESH").contains("caustiqIrisVertexMain"));
            assertTrue(combined.get("FRAGMENT").contains("caustiqIrisFragmentMain"));
            assertTrue(combined.get("MESH").contains("gl_MeshVerticesNV[id].gl_Position = caustiqIrisClipPosition;"));
            assertFalse(combined.get("FRAGMENT").contains("layout(location = 1) out vec4 caustiqIrisOutput1;"),
                    "Photon's default RENDERTARGETS: 1 must not expose its disabled second MRT output");
            String outputDirectory = System.getenv("CAUSTIQ_COMPAT_OUTPUT_DIR");
            String validator = System.getenv("GLSLANG_VALIDATOR");
            Assumptions.assumeTrue(outputDirectory != null && validator != null,
                    "GLSL integration compilation is enabled in CI");
            Path output = Path.of(outputDirectory);
            Files.createDirectories(output);
            String mesh = prepareForGlslang(combined.get("MESH"));
            String fragment = prepareForGlslang(combined.get("FRAGMENT"));
            Path meshFile = output.resolve("photon-nvidium-terrain.mesh");
            Path fragmentFile = output.resolve("photon-nvidium-terrain.frag");
            Files.writeString(meshFile, mesh);
            Files.writeString(fragmentFile, fragment);
            compileGlsl(validator, "mesh", meshFile);
            compileGlsl(validator, "frag", fragmentFile);
        }
    }

    private static String prepareForGlslang(String source) {
        String prepared = source
                .replace("#extension GL_ARB_shading_language_include : enable", "")
                .replaceAll("(?m)^\\s*#pragma optionNV[^\\r\\n]*\\R?", "")
                .replaceAll("(?m)^\\s*#import <sodium:[^>]+>\\s*\\R?", "");
        return prepared.replaceFirst("(?m)^(#version[^\\r\\n]*\\R)",
                "$1#define USE_SODIUM_VERTEX_FORMAT 1\\n");
    }

    private static void compileGlsl(String validator, String stage, Path source) throws Exception {
        Process process = new ProcessBuilder(validator, "-S", stage, source.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("glslangValidator timed out compiling " + stage + " stage.");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("glslangValidator rejected the combined Photon/Nvidium "
                    + stage + " stage:\\n" + output);
        }
    }

    private static Map<String, String> pinnedNvidiumStages() throws Exception {
        String root = System.getenv("NVIDIUM_SOURCE_DIR");
        Assumptions.assumeTrue(root != null && !root.isBlank(),
                "Pinned Nvidium source checkout is supplied by CI only");
        Path shaderRoot = Path.of(root, "src/main/resources/assets/nvidium/shaders");
        return Map.of(
                "TASK", expandNvidiumImports(Files.readString(shaderRoot.resolve("terrain/task.glsl")), shaderRoot, Set.of()),
                "MESH", expandNvidiumImports(Files.readString(shaderRoot.resolve("terrain/mesh.glsl")), shaderRoot, Set.of()),
                "FRAGMENT", expandNvidiumImports(Files.readString(shaderRoot.resolve("terrain/frag.frag")), shaderRoot, Set.of()));
    }

    private static String expandNvidiumImports(String source, Path shaderRoot, Set<String> ancestors)
            throws Exception {
        Pattern imports = Pattern.compile("(?m)^\\s*#import\\s*<nvidium:([^>]+)>\\s*$");
        Matcher matcher = imports.matcher(source);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String resource = matcher.group(1);
            if (ancestors.contains(resource)) {
                throw new IllegalStateException("Cyclic Nvidium shader import: " + resource);
            }
            Path importedPath = shaderRoot.resolve(resource).normalize();
            if (!importedPath.startsWith(shaderRoot) || !Files.isRegularFile(importedPath)) {
                throw new IllegalStateException("Missing pinned Nvidium shader import: " + resource);
            }
            Set<String> nextAncestors = new java.util.LinkedHashSet<>(ancestors);
            nextAncestors.add(resource);
            String imported = expandNvidiumImports(
                    Files.readString(importedPath), shaderRoot, Set.copyOf(nextAncestors));
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(imported));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static Map<String, String> nativeStages() {
        String mesh = """
                #version 460
                layout(triangles, max_vertices=64, max_primitives=32) out;
                struct Vertex { uint blockId; uint normal; uint midUV; uint midBlock; uint nvidiumMaterial; };
                layout(location=1) out Interpolants {
                    vec2 uv;
                    vec3 v_colour;
                } OUT[];
                vec4 transformVertex(Vertex V) { return vec4(0.0); }
                void putVertex(uint id, Vertex V) {
                    OUT[id].v_materialFlags = V.nvidiumMaterial & 0xFFu;
                }
                void main() {}
                """;
        String fragment = """
                #version 460
                layout(location = 0) out vec4 colour;
                layout(location = 1) out vec4 caustiqGBufferNormal;
                layout(location = 2) out vec4 caustiqGBufferPosition;
                layout(location = 3) out vec4 caustiqGBufferMaterial;
                layout(location = 4) out vec4 caustiqGBufferLightmap;
                layout(location=1) in Interpolants {
                    vec2 uv;
                    vec3 v_colour;
                };
                void main() {
                    colour = vec4(1.0);
                    caustiqGBufferNormal = vec4(1.0);
                    caustiqGBufferPosition = vec4(1.0);
                    caustiqGBufferMaterial = vec4(1.0);
                    caustiqGBufferLightmap = vec4(1.0);
                }
                """;
        return Map.of("TASK", "#version 460\nvoid main() {}",
                "MESH", mesh, "FRAGMENT", fragment);
    }
}
