package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumIrisTerrainAbiTest {
    @Test
    void exposesOnlyAttributesPresentOrDerivableFromPinnedNvidiumFormat() {
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of(
                "gl_Vertex", "gl_MultiTexCoord0", "gl_MultiTexCoord1",
                "vaPosition", "vaUV0", "vaUV2", "vaColor", "gl_Color", "lmcoord")));
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("gl_Normal")));
        assertFalse(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("viewSpacePosition")));
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("mc_Entity")));
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("at_midTexCoord")));
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("at_midBlock")));
        assertTrue(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("at_tangent")));
        assertFalse(NvidiumIrisTerrainAbi.canRepresentRequiredVertexInputs(List.of("unknownPackAttribute")));
    }

    @Test
    void reportsExactNvidiumSamplerAndFramebufferUniformBindings() {
        assertEquals(0, NvidiumIrisTerrainAbi.DIFFUSE_ATLAS_TEXTURE_UNIT);
        assertEquals(1, NvidiumIrisTerrainAbi.LIGHTMAP_TEXTURE_UNIT);
        assertEquals("tex_diffuse (texture unit 0)",
                NvidiumIrisTerrainAbi.fragmentSemantic("gtexture").orElseThrow().meshExpression());
        assertEquals("tex_light (texture unit 1)",
                NvidiumIrisTerrainAbi.fragmentSemantic("lightmap").orElseThrow().meshExpression());
        assertEquals("screenSize.x * 2.0",
                NvidiumIrisTerrainAbi.fragmentSemantic("viewWidth").orElseThrow().meshExpression());
        assertEquals("fogColour",
                NvidiumIrisTerrainAbi.fragmentSemantic("fogColor").orElseThrow().meshExpression());
    }

    @Test
    void emitsLineSeparatedAliasesForOnlyBoundNvidiumResources() {
        String aliases = NvidiumIrisTerrainAbi.fragmentAliasDeclarations();
        assertTrue(aliases.contains("#define CAUSTIQ_IRIS_GTEXTURE tex_diffuse"));
        assertTrue(aliases.contains("#define CAUSTIQ_IRIS_LIGHTMAP tex_light"));
        assertTrue(aliases.contains("#define CAUSTIQ_IRIS_FOG_COLOR fogColour"));
        assertTrue(aliases.contains("#define CAUSTIQ_IRIS_VIEW_WIDTH (screenSize.x * 2.0)"));
        assertTrue(aliases.contains("\n"));
        assertFalse(aliases.contains("\\n"));
    }

    @Test
    void distinguishesUnavailableViewPositionFromPreservedIrisNormalData() {
        assertEquals(NvidiumIrisTerrainAbi.Availability.UNAVAILABLE,
                NvidiumIrisTerrainAbi.vertexSemantic("viewSpacePosition").orElseThrow().availability());
        assertEquals(NvidiumIrisTerrainAbi.Availability.EXACT,
                NvidiumIrisTerrainAbi.vertexSemantic("gl_Normal").orElseThrow().availability());
        assertTrue(NvidiumIrisTerrainAbi.vertexSemantic("gl_Normal").orElseThrow()
                .explanation().contains("octahedral face normals"));
    }
}
