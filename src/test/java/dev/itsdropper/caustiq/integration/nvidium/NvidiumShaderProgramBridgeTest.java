package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NvidiumShaderProgramBridgeTest {
    @BeforeEach void resetBefore() {
        NvidiumShaderProgramBridge.resetForTests();
        NvidiumCompatibility.setExtendedVertexFormatCommittedForTests(true);
    }
    @AfterEach void reset() {
        NvidiumShaderProgramBridge.resetForTests();
        NvidiumCompatibility.setExtendedVertexFormatCommittedForTests(false);
    }

    @Test void nativeAdapterIsNotActiveUntilTheExtendedVertexFormatIsRequested() {
        NvidiumCompatibility.setExtendedVertexFormatCommittedForTests(false);
        assertFalse(NvidiumShaderProgramBridge.hasNativeSourceAdapter());
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertEquals("mesh source content", sources.get(Stage.MESH));
        assertFalse(NvidiumShaderProgramBridge.hasNativeSourceAdapter());
    }
    @Test void capturesTerrainStagesAtCompileBoundary() {
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        var snapshot = NvidiumShaderProgramBridge.lastTerrainProgram();
        assertNotNull(snapshot);
        assertEquals(3, snapshot.stages().size());
        assertEquals("task source content".length(), snapshot.stages().get("TASK").sourceLength());
        assertFalse(NvidiumShaderProgramBridge.hasTerrainAdapter());
    }

    @Test void appliesAdapterToAllTerrainStages() {
        NvidiumShaderProgramBridge.registerTerrainAdapter(sources -> Map.of(
                "TASK", sources.get("TASK") + "\n// adapted",
                "MESH", sources.get("MESH") + "\n// adapted",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// adapted"));
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertTrue(NvidiumShaderProgramBridge.hasTerrainAdapter());
        assertTrue(sources.get(Stage.TASK).endsWith("// adapted"));
        assertTrue(sources.get(Stage.MESH).endsWith("// adapted"));
        assertTrue(sources.get(Stage.FRAGMENT).endsWith("// adapted"));
    }

    @Test void rejectsAdaptersThatDropStages() {
        NvidiumShaderProgramBridge.registerTerrainAdapter(sources -> Map.of("FRAGMENT", "void main(){}"));
        assertThrows(IllegalStateException.class,
                () -> NvidiumShaderProgramBridge.transformBeforeCompile(terrainSources()));
    }


    @Test void permitsNoOpAdapterForNonPrimaryNvidiumPipelines() {
        NvidiumShaderProgramBridge.registerTerrainAdapter(Map::copyOf);
        Map<Object, String> sources = terrainSources();
        assertDoesNotThrow(() -> NvidiumShaderProgramBridge.transformBeforeCompile(sources));
        assertEquals("mesh source content", sources.get(Stage.MESH));
    }

    @Test void rejectsNoOpAdapterForPrimaryTerrainPipeline() {
        NvidiumShaderProgramBridge.registerTerrainAdapter(Map::copyOf);
        Map<Object, String> sources = new LinkedHashMap<>();
        sources.put(Stage.TASK, "task source");
        sources.put(Stage.MESH,
                "#version 460\\nlayout(triangles, max_vertices=64, max_primitives=32) out;");
        sources.put(Stage.FRAGMENT, "fragment source");
        assertThrows(IllegalStateException.class,
                () -> NvidiumShaderProgramBridge.transformBeforeCompile(sources));
    }

    @Test void resetClearsCapturedProgramAndAdapter() {
        NvidiumShaderProgramBridge.registerTerrainAdapter(sources -> Map.of(
                "TASK", sources.get("TASK") + "\n// adapter",
                "MESH", sources.get("MESH") + "\n// adapter",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// adapter"));
        NvidiumShaderProgramBridge.transformBeforeCompile(terrainSources());
        NvidiumShaderProgramBridge.resetForTests();
        assertNull(NvidiumShaderProgramBridge.lastTerrainProgram());
        assertFalse(NvidiumShaderProgramBridge.hasTerrainAdapter());
    }


    @Test void nativeVaryingAdapterDoesNotEnableIrisCompatibility() {
        NvidiumShaderProgramBridge.registerNativeSourceAdapter(sources -> Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH") + "\n// native varying profile",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// native varying profile"));
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertTrue(sources.get(Stage.MESH).endsWith("// native varying profile"));
        assertFalse(NvidiumShaderProgramBridge.hasTerrainAdapter(),
                "Native varying support must not bypass Nvidium's Iris safety gate");
    }

    @Test void nativeAdapterCanSafelySeeAlreadyAdaptedSources() {
        NvidiumShaderProgramBridge.registerNativeSourceAdapter(sources -> {
            if (sources.get("MESH").contains("native-profile")) return sources;
            return Map.of(
                    "TASK", sources.get("TASK"),
                    "MESH", sources.get("MESH") + "\n// native-profile",
                    "FRAGMENT", sources.get("FRAGMENT") + "\n// native-profile");
        });
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertDoesNotThrow(() -> NvidiumShaderProgramBridge.transformBeforeCompile(sources));
    }

    @Test void nativeAndIrisAdaptersRunInOrder() {
        NvidiumShaderProgramBridge.registerNativeSourceAdapter(sources -> Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH") + "\n// native",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// native"));
        NvidiumShaderProgramBridge.registerTerrainAdapter(sources -> Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH") + "\n// iris",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// iris"));
        Map<Object, String> sources = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertTrue(sources.get(Stage.MESH).endsWith("// native\n// iris"));
        assertTrue(NvidiumShaderProgramBridge.hasTerrainAdapter());
    }

    @Test void appliesDedicatedAdapterOnlyInsideShadowCompileScope() {
        NvidiumShaderProgramBridge.registerShadowTerrainAdapter(sources -> Map.of(
                "TASK", sources.get("TASK"),
                "MESH", sources.get("MESH") + "\n// iris shadow",
                "FRAGMENT", sources.get("FRAGMENT") + "\n// iris shadow"));
        NvidiumShaderProgramBridge.beginPipelineCompile(NvidiumShaderProgramBridge.PipelineKind.SHADOW);
        try {
            Map<Object, String> sources = terrainSources();
            NvidiumShaderProgramBridge.transformBeforeCompile(sources);
            assertTrue(sources.get(Stage.MESH).endsWith("// iris shadow"));
            assertTrue(sources.get(Stage.FRAGMENT).endsWith("// iris shadow"));
        } finally {
            NvidiumShaderProgramBridge.endPipelineCompile();
        }
        Map<Object, String> primary = terrainSources();
        NvidiumShaderProgramBridge.transformBeforeCompile(primary);
        assertFalse(primary.get(Stage.MESH).contains("iris shadow"));
    }

    @Test void ignoresNonTerrainPrograms() {
        Map<Object, String> sources = new LinkedHashMap<>();
        sources.put(Stage.COMPUTE, "compute source");
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
        assertNull(NvidiumShaderProgramBridge.lastTerrainProgram());
    }

    private static Map<Object, String> terrainSources() {
        Map<Object, String> sources = new LinkedHashMap<>();
        sources.put(Stage.TASK, "task source content");
        sources.put(Stage.MESH, "mesh source content");
        sources.put(Stage.FRAGMENT, "fragment source content");
        return sources;
    }
    private enum Stage { TASK, MESH, FRAGMENT, COMPUTE }
}
