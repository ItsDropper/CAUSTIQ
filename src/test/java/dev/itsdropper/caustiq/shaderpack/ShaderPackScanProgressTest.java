package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackScanProgressTest {
    @Test
    void directoryDiscoveryIsIndeterminateAndContainsNoCandidate() {
        ShaderPackScanProgress progress = ShaderPackScanProgress.discoveringCandidates();

        assertEquals(ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES, progress.stage());
        assertFalse(progress.isDeterminate());
        assertEquals(0.0, progress.fraction());
        assertEquals("", progress.currentCandidate());
    }

    @Test
    void inspectionProgressIsBoundedAndCalculatesFraction() {
        ShaderPackScanProgress progress = ShaderPackScanProgress.inspectingCandidates(3, 8, "Pack.zip");

        assertTrue(progress.isDeterminate());
        assertEquals(0.375, progress.fraction(), 0.000001);
        assertEquals("Pack.zip", progress.currentCandidate());
    }

    @Test
    void candidateLabelsExposeOnlyTheFilename() {
        ShaderPackScanProgress progress = ShaderPackScanProgress.inspectingCandidates(
                0, 1, "/home/player/.minecraft/shaderpacks/PrivatePack.zip");

        assertEquals("PrivatePack.zip", progress.currentCandidate());
        assertFalse(progress.currentCandidate().contains("/"));
    }

    @Test
    void candidateLabelsAreBounded() {
        String longName = "a".repeat(100) + ".zip";
        ShaderPackScanProgress progress = ShaderPackScanProgress.inspectingCandidates(0, 1, longName);

        assertEquals(48, progress.currentCandidate().length());
        assertTrue(progress.currentCandidate().endsWith("..."));
    }

    @Test
    void rejectsImpossibleCountsAndDiscoveryPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackScanProgress.inspectingCandidates(2, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackScanProgress(
                        ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES, 0, 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackScanProgress.inspectingCandidates(1, 1, "Already finished"));
    }
}
