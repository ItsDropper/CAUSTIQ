package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IrisGlPassExecutorTest {
    @Test
    void preservesNonIdentityFragmentOutputToAttachmentRouting() {
        assertArrayEquals(new int[]{
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT2
                },
                IrisGlPassExecutor.createDrawBuffers(Map.of(0, 0, 1, 3, 2, 7), Set.of(0, 3, 7)));
    }

    @Test
    void keepsSparseFragmentOutputSlotsDisabled() {
        assertArrayEquals(new int[]{
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL11C.GL_NONE,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT2
                },
                IrisGlPassExecutor.createDrawBuffers(Map.of(0, 0, 2, 5), Set.of(0, 5)));
    }

    @Test
    void routesFullscreenOutputsBeyondNvidiumsTerrainAttachmentRange() {
        assertArrayEquals(new int[]{
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1
                },
                IrisGlPassExecutor.createDrawBuffers(Map.of(0, 8, 1, 12), Set.of(8, 12)));
    }

    @Test
    void onlyTreatsNvidiumTerrainProgramsAsTerrainMrtWriters() {
        assertTrue(IrisGlPassExecutor.isNvidiumTerrainProgram("gbuffers_terrain"));
        assertTrue(IrisGlPassExecutor.isNvidiumTerrainProgram("gbuffers_water"));
        assertFalse(IrisGlPassExecutor.isNvidiumTerrainProgram("gbuffers_entities"));
        assertFalse(IrisGlPassExecutor.isNvidiumTerrainProgram("gbuffers_hand"));
    }

    @Test
    void recognizesPhotonSceneDepthAndPbrSamplerBindings() {
        assertEquals(IrisGlPassExecutor.BuiltinSamplerKind.DEPTH0,
                IrisGlPassExecutor.builtinSamplerKind("depthtex0"));
        assertEquals(IrisGlPassExecutor.BuiltinSamplerKind.DEPTH1,
                IrisGlPassExecutor.builtinSamplerKind("depthtex1"));
        assertEquals(IrisGlPassExecutor.BuiltinSamplerKind.NORMALS,
                IrisGlPassExecutor.builtinSamplerKind("normals"));
        assertEquals(IrisGlPassExecutor.BuiltinSamplerKind.SPECULAR,
                IrisGlPassExecutor.builtinSamplerKind("specular"));
        assertEquals(IrisGlPassExecutor.BuiltinSamplerKind.NONE,
                IrisGlPassExecutor.builtinSamplerKind("depthtex2"));
    }

    @Test
    void rejectsMappingsToUnattachedTargets() {
        assertThrows(IllegalStateException.class,
                () -> IrisGlPassExecutor.createDrawBuffers(Map.of(0, 3), Set.of(0)));
    }
}
