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
                        Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false", "TAA", "false"));
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

            String shadowProgramName = java.util.stream.Stream.concat(
                            model.base().programs().keySet().stream(),
                            model.dimensionOverrides().values().stream()
                                    .flatMap(programSet -> programSet.programs().keySet().stream()))
                    .filter(name -> name.equalsIgnoreCase("shadow")
                            || name.toLowerCase(java.util.Locale.ROOT).startsWith("shadow_"))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "Photon source fixture must expose an Iris shadow terrain program"));
            IrisNvidiumTerrainTranslator shadowTranslator = IrisNvidiumTerrainTranslator.from(
                    model, "world0", shadowProgramName,
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false", "TAA", "false"));
            assertEquals(List.of(0), shadowTranslator.renderTargetIndices(),
                    "Photon shadow color must map to Iris shadowcolor0, not the G-buffer colortex0");
            Map<String, String> shadowStages = shadowTranslator.adapt(
                    NvidiumTerrainVaryingAdapter.adapt(pinnedNvidiumStages()));
            assertTrue(shadowStages.get("MESH").contains("void caustiqIrisVertexMain()"),
                    "Photon shadow vertex stage was not translated into Nvidium's mesh stage");
            assertTrue(shadowStages.get("MESH").contains("caustiqShadowProjection"),
                    "Photon shadow vertex stage did not use the light-space projection");
            assertTrue(shadowStages.get("FRAGMENT").contains("void caustiqIrisFragmentMain()"),
                    "Photon shadow fragment stage was not translated into Nvidium's fragment stage");
            String shadowOutputDirectory = System.getenv("CAUSTIQ_COMPAT_OUTPUT_DIR");
            if (shadowOutputDirectory != null && !shadowOutputDirectory.isBlank()) {
                Path output = Path.of(shadowOutputDirectory);
                Files.createDirectories(output);
                Files.writeString(output.resolve("photon-nvidium-shadow.mesh"), shadowStages.get("MESH"));
                Files.writeString(output.resolve("photon-nvidium-shadow.frag"), shadowStages.get("FRAGMENT"));
            }

            IrisNvidiumTerrainTranslator defaults = IrisNvidiumTerrainTranslator.from(
                    model, "world0", "gbuffers_terrain",
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false", "TAA", "false"));
            assertEquals(List.of(1), defaults.renderTargetIndices());
        }
    }

    @Test
    void translatesIrisHeavyFogBooleanToTheRuntimeUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform bool heavyFog;
                void main() {
                    gl_FragColor = heavyFog ? vec4(1.0) : vec4(0.0);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform bool caustiqHeavyFog;"));
        assertTrue(fragment.contains("caustiqHeavyFog ? vec4(1.0) : vec4(0.0)"));
        assertFalse(fragment.contains("uniform bool heavyFog;"));
    }

    @Test
    void translatesIrisElytraFlyingBooleanToTheRuntimeUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform bool isElytraFlying;
                void main() {
                    gl_FragColor = isElytraFlying ? vec4(1.0) : vec4(0.0);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform bool caustiqIsElytraFlying;"));
        assertTrue(fragment.contains("caustiqIsElytraFlying ? vec4(1.0) : vec4(0.0)"));
        assertFalse(fragment.contains("uniform bool isElytraFlying;"));
    }

    @Test
    void translatesIrisEyeInWaterIntegerToTheRuntimeCameraFluidUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform int isEyeInWater;
                void main() {
                    gl_FragColor = vec4(float(isEyeInWater));
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform int caustiqIsEyeInWater;"));
        assertTrue(fragment.contains("float(caustiqIsEyeInWater)"));
        assertFalse(fragment.contains("uniform int isEyeInWater;"));
    }

    @Test
    void translatesHeldBlockLightValueToTheIrisItemLightRuntimeUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform int heldBlockLightValue;
                void main() {
                    gl_FragColor = vec4(float(heldBlockLightValue) / 15.0);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform int caustiqHeldBlockLightValue;"));
        assertTrue(fragment.contains("float(caustiqHeldBlockLightValue) / 15.0"));
        assertFalse(fragment.contains("uniform int heldBlockLightValue;"));
    }

    @Test
    void translatesHeldItemIdToTheRuntimeItemRegistryUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform int heldItemId;
                void main() { gl_FragColor = vec4(float(heldItemId)); }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform int caustiqHeldItemId;"));
        assertTrue(fragment.contains("float(caustiqHeldItemId)"));
        assertFalse(fragment.contains("uniform int heldItemId;"));
    }

    @Test
    void translatesOffHandHeldItemIdToItsRuntimeItemRegistryUniform() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform int heldItemId2;
                void main() { gl_FragColor = vec4(float(heldItemId2)); }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform int caustiqHeldItemId2;"));
        assertTrue(fragment.contains("float(caustiqHeldItemId2)"));
        assertFalse(fragment.contains("uniform int heldItemId2;"));
    }

    @Test
    void translatesMainAndOffHandHeldBlockLightUniformsToSeparateRuntimeValues() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform int heldBlockLightValue;
                uniform int heldBlockLightValue2;
                void main() {
                    gl_FragColor = vec4(float(heldBlockLightValue + heldBlockLightValue2) / 30.0);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("uniform int caustiqHeldBlockLightValue;"));
        assertTrue(fragment.contains("uniform int caustiqHeldBlockLightValue2;"));
        assertTrue(fragment.contains("caustiqHeldBlockLightValue + caustiqHeldBlockLightValue2"));
        assertFalse(fragment.contains("uniform int heldBlockLightValue;"));
        assertFalse(fragment.contains("uniform int heldBlockLightValue2;"));
    }

    @Test
    void rejectsIrisHeavyFogWithAnIncorrectUniformType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                translator("""
                        #version 150
                        void main() { gl_Position = ftransform(); }
                        """, """
                        #version 150
                        uniform float heavyFog;
                        void main() { gl_FragColor = vec4(heavyFog); }
                        """));
        assertTrue(exception.getMessage().contains("heavyFog"));
        assertTrue(exception.getMessage().contains("bool"));
    }

    @Test
    void translatesGlFragDataToNvidiumMrtLocations() {
        IrisNvidiumTerrainTranslator translator = translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                /* RENDERTARGETS: 1,2,3,4 */
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.5);
                    gl_FragData[3] = vec4(0.25);
                }
                """);

        String fragment = translator.adapt(nativeStages()).get("FRAGMENT");
        assertTrue(fragment.contains("layout(location = 1) out vec4 caustiqIrisOutput0;"));
        assertTrue(fragment.contains("layout(location = 2) out vec4 caustiqIrisOutput1;"));
        assertTrue(fragment.contains("layout(location = 4) out vec4 caustiqIrisOutput3;"));
        assertTrue(fragment.contains("caustiqIrisOutput0 = caustiqIrisFragData[0];"));
        assertTrue(fragment.contains("caustiqIrisOutput1 = caustiqIrisFragData[1];"));
        assertTrue(fragment.contains("caustiqIrisOutput3 = caustiqIrisFragData[3];"));
    }

    @Test
    void rejectsUnavailableSamplersOnlyWhenTheirFunctionsAreReachableFromMain() {
        assertDoesNotThrow(() -> translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform sampler2D depthtex1;
                vec4 unusedDepthHelper() { return texture(depthtex1, vec2(0.0)); }
                void main() { gl_FragColor = vec4(1.0); }
                """));
        assertThrows(IllegalArgumentException.class, () -> translator("""
                #version 150
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                uniform sampler2D depthtex1;
                void main() { gl_FragColor = texture(depthtex1, vec2(0.0)); }
                """));
    }

    @Test
    void rejectsUnavailableUniformsAndUnmatchedVaryingsBeforeShaderLink() {
        assertDoesNotThrow(() -> translator("""
                #version 150
                uniform sampler2D shadowtex0;
                void main() { gl_Position = ftransform(); }
                """, """
                #version 150
                void main() { gl_FragColor = texture2D(shadowtex0, vec2(0.0)); }
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
    void translatesIrisShadowProgramAgainstLightSpaceMatrices() {
        Map<ShaderPackProgramCatalog.Stage, String> stages = new LinkedHashMap<>();
        stages.put(ShaderPackProgramCatalog.Stage.VERTEX, "shaders/shadow.vsh");
        stages.put(ShaderPackProgramCatalog.Stage.FRAGMENT, "shaders/shadow.fsh");
        IrisShaderPackModel.Program program = new IrisShaderPackModel.Program("shadow", stages);
        String vertex = """
                #version 150
                uniform mat4 shadowModelView;
                uniform mat4 shadowProjection;
                void main() {
                    gl_Position = shadowProjection * shadowModelView * gl_Vertex;
                }
                """;
        String fragment = """
                #version 150
                void main() { gl_FragColor = vec4(1.0); }
                """;
        IrisShaderPackModel model = new IrisShaderPackModel(
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), null,
                new IrisShaderPackModel.ProgramSet("", Map.of("shadow", program)),
                Map.of(), Map.of("shaders/shadow.vsh", vertex, "shaders/shadow.fsh", fragment), List.of());
        IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                model, null, "shadow", Map.of());
        Map<String, String> translated = translator.adapt(nativeStages());
        assertTrue(translated.get("MESH").contains("caustiqShadowProjection"));
        assertTrue(translated.get("MESH").contains("caustiqShadowModelView"));
        assertFalse(translated.get("MESH").contains("caustiqProjection * caustiqModelView"));
        assertTrue(translated.get("FRAGMENT").contains("caustiqIrisFragmentMain"));
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
                    Map.of("NORMAL_MAPPING", "false", "SPECULAR_MAPPING", "false", "TAA", "false"));
            Map<String, String> combined = translator.adapt(extended);
            assertTrue(combined.get("MESH").contains("caustiqIrisVertexMain"));
            assertTrue(combined.get("FRAGMENT").contains("caustiqIrisFragmentMain"));
            assertTrue(combined.get("MESH").contains("gl_MeshVerticesNV[id].gl_Position = caustiqIrisClipPosition;"));
            assertTrue(combined.get("FRAGMENT").contains("layout(location = 0) out vec4 colour;"),
                    "Nvidium's native forward color output must remain at location zero");
            assertTrue(combined.get("FRAGMENT").contains("layout(location = 1) out vec4 caustiqIrisOutput0;"),
                    "Iris colortex1 must be routed through a separate output location");
            String outputDirectory = System.getenv("CAUSTIQ_COMPAT_OUTPUT_DIR");
            Assumptions.assumeTrue(outputDirectory != null && !outputDirectory.isBlank(),
                    "Combined Photon/Nvidium source artifacts are captured in CI");
            Path output = Path.of(outputDirectory);
            Files.createDirectories(output);
            String mesh = combined.get("MESH");
            String fragment = combined.get("FRAGMENT");
            Path meshFile = output.resolve("photon-nvidium-terrain.mesh");
            Path fragmentFile = output.resolve("photon-nvidium-terrain.frag");
            Files.writeString(meshFile, mesh);
            Files.writeString(fragmentFile, fragment);
            assertTrue(mesh.contains("uint16_t *regionIndicies"),
                    "The pinned Nvidium mesh dialect must retain its buffer-reference pointer ABI.");
            assertTrue(mesh.contains("layout(triangles"),
                    "The translated mesh stage must retain Nvidium's native mesh execution contract.");
            assertTrue(fragment.contains("uint16_t *regionIndicies"),
                    "The pinned Nvidium shared ABI declarations must remain intact in the fragment stage.");
            assertTrue(fragment.contains("layout(location = 1) out vec4 caustiqIrisOutput0;"),
                    "The composed fragment source must preserve Iris colortex1 output routing.");
            // The upstream Nvidium shader dialect embeds buffer-reference pointer declarations
            // in shared stage includes. glslangValidator cannot parse that dialect, even when
            // those declarations are unused by the fragment entry point. Keep the exact combined
            // sources as CI artifacts and assert ABI/routing contracts instead of treating an
            // incompatible compiler as a correctness oracle.
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
