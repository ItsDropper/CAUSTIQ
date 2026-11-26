package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackRefreshStatusTest {
    @Test
    void exposesScanningWithoutInventingPartialResults() {
        ShaderPackRefreshStatus status = ShaderPackRefreshStatus.scanning();

        assertTrue(status.isScanning());
        assertEquals(ShaderPackRefreshStatus.State.SCANNING, status.state());
        assertEquals(0, status.packCount());
        assertEquals(0, status.rejectedCandidateCount());
        assertEquals("", status.failureMessage());
    }

    @Test
    void publishesCountsOnlyAfterTheScanCompletes() {
        ShaderPackDescriptor pack = new ShaderPackDescriptor(
                "example", "Example", Path.of("Example"), "",
                ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 128);
        ShaderPackScanResult result = new ShaderPackScanResult(
                List.of(pack), List.of(new ShaderPackIssue(Path.of("Broken.zip"), "Invalid ZIP")), 3);

        ShaderPackRefreshStatus status = ShaderPackRefreshStatus.complete(result);

        assertFalse(status.isScanning());
        assertEquals(ShaderPackRefreshStatus.State.COMPLETE, status.state());
        assertEquals(1, status.packCount());
        assertEquals(1, status.rejectedCandidateCount());
        assertEquals("", status.failureMessage());
    }

    @Test
    void representsFailureSeparatelyFromAnEmptySuccessfulScan() {
        ShaderPackRefreshStatus failed = ShaderPackRefreshStatus.failed();
        ShaderPackRefreshStatus empty = ShaderPackRefreshStatus.complete(
                new ShaderPackScanResult(List.of(), List.of(), 0));

        assertEquals(ShaderPackRefreshStatus.State.FAILED, failed.state());
        assertEquals(ShaderPackRefreshStatus.State.COMPLETE, empty.state());
    }

    @Test
    void preservesSanitizedFailureDetailsForTheUser() {
        ShaderPackRefreshStatus failed = ShaderPackRefreshStatus.failed(
                "Unable to access /home/player/.minecraft/shaderpacks: Permission denied");

        assertEquals(ShaderPackRefreshStatus.State.FAILED, failed.state());
        assertEquals("Unable to access [path]: Permission denied", failed.failureMessage());
    }

    @Test
    void rejectsFailureDetailsForNonFailureStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackRefreshStatus(ShaderPackRefreshStatus.State.COMPLETE, 0, 0, "failure"));
    }


    @Test
    void startsWithIndeterminateDiscoveryThenCarriesDeterminateCandidateProgress() {
        ShaderPackRefreshStatus discovering = ShaderPackRefreshStatus.scanning();
        assertEquals(ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES, discovering.progress().stage());
        assertFalse(discovering.isDeterminate());

        ShaderPackScanProgress progress = ShaderPackScanProgress.inspectingCandidates(2, 5, "Example.zip");
        ShaderPackRefreshStatus inspecting = ShaderPackRefreshStatus.scanning(progress);
        assertTrue(inspecting.isScanning());
        assertTrue(inspecting.isDeterminate());
        assertEquals(0.4, inspecting.progressFraction(), 0.000001);
        assertEquals(progress, inspecting.progress());
    }

    @Test
    void rejectsScanProgressOutsideScanningState() {
        ShaderPackScanProgress progress = ShaderPackScanProgress.inspectingCandidates(1, 2, "Pack");
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackRefreshStatus(
                        ShaderPackRefreshStatus.State.COMPLETE, 0, 0, "", progress));
    }

    @Test
    void rejectsImpossiblePartialCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackRefreshStatus(ShaderPackRefreshStatus.State.SCANNING, 1, 0));
    }
}
