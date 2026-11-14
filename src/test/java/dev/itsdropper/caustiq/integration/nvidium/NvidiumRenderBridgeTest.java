package dev.itsdropper.caustiq.integration.nvidium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NvidiumRenderBridgeTest {
    @AfterEach
    void reset() {
        NvidiumRenderBridge.resetForTests();
    }

    @Test
    void recordsCompletedSolidTerrainPasses() {
        NvidiumRenderBridge.solidPassStarted();
        assertFalse(NvidiumRenderBridge.snapshot().hasCompletedSolidRenderCall());
        NvidiumRenderBridge.solidPassCompleted();

        NvidiumRenderBridge.Snapshot snapshot = NvidiumRenderBridge.snapshot();
        assertEquals(1, snapshot.startedSolidPasses());
        assertEquals(1, snapshot.completedSolidPasses());
        assertTrue(snapshot.hasCompletedTerrainPass());
        assertFalse(snapshot.hasUnfinishedPass());
    }

    @Test
    void detectsTerrainPassThatDidNotReturnNormally() {
        NvidiumRenderBridge.solidPassStarted();
        assertTrue(NvidiumRenderBridge.snapshot().hasUnfinishedPass());
        assertEquals(0, NvidiumRenderBridge.snapshot().completedSolidPasses());
    }

    @Test
    void tracksTranslucentPassesIndependently() {
        NvidiumRenderBridge.translucentPassStarted();
        NvidiumRenderBridge.translucentPassCompleted();

        NvidiumRenderBridge.Snapshot snapshot = NvidiumRenderBridge.snapshot();
        assertEquals(1, snapshot.startedTranslucentPasses());
        assertEquals(1, snapshot.completedTranslucentPasses());
        assertFalse(snapshot.hasCompletedTerrainPass());
    }
}
