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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertTrue(mesh.contains("OUT[id].texCoord = caustiqIrisVarying_texCoord;"));
        assertTrue(fragment.contains("void caustiqIrisFragmentMain()"));
        assertTrue(fragment.contains("texture(tex_diffuse, texCoord)"));
        assertTrue(fragment.contains("caustiqIrisTexCoord[0] = v_irisTexCoord0;"));
        assertFalse(fragment.contains("caustiqGBufferNormal"));
    }

    @Test
    void translatesPinnedPhotonTerrainIntoTheRealNvidiumStageAbiWhenCiProvidesItsSource() throws Exception {
        String photonDirectory = System.getenv("PHOTON_SOURCE_DIR");
        Assumptions.assumeTrue(photonDirectory != null && !photonDirectory.isBlank(),
                "Photon source checkout is supplied by CI only");
        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                Path.of(photonDirectory), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(model);
            Map<String, String> translated = translator.adapt(nativeStages());
            assertTrue(translated.get("MESH").contains("void caustiqIrisVertexMain()"));
            assertTrue(translated.get("FRAGMENT").contains("void caustiqIrisFragmentMain()"));
            assertTrue(translated.get("MESH").contains("OUT[id].scene_pos ="));
            assertTrue(translated.get("FRAGMENT").contains("gbuffer_data_0"));
            assertEquals(List.of(1), translator.renderTargetIndices());
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
                    OUT[id].v_materialFlags = V.nvidiumMaterial;
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
