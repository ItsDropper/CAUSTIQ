package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NvidiumShaderProgramBridgeTest {
    @AfterEach void reset() { NvidiumShaderProgramBridge.unregisterTerrainAdapter(); }

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
