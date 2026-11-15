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
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT3,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT7
                },
                IrisGlPassExecutor.createDrawBuffers(Map.of(0, 0, 1, 3, 2, 7), Set.of(0, 3, 7)));
    }

    @Test
    void keepsSparseFragmentOutputSlotsDisabled() {
        assertArrayEquals(new int[]{
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL11C.GL_NONE,
                        org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT5
                },
                IrisGlPassExecutor.createDrawBuffers(Map.of(0, 0, 2, 5), Set.of(0, 5)));
    }

    @Test
    void rejectsMappingsToUnattachedTargets() {
        assertThrows(IllegalStateException.class,
                () -> IrisGlPassExecutor.createDrawBuffers(Map.of(0, 3), Set.of(0)));
    }
}
