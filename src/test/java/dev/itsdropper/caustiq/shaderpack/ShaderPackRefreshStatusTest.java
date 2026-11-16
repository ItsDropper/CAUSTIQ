package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void publishesCountsOnlyAfterTheScanCompletes() {
        ShaderPackScanResult result = new ShaderPackScanResult(List.of(), List.of(), 3);

        ShaderPackRefreshStatus status = ShaderPackRefreshStatus.complete(result);

        assertFalse(status.isScanning());
        assertEquals(ShaderPackRefreshStatus.State.COMPLETE, status.state());
        assertEquals(0, status.packCount());
        assertEquals(0, status.rejectedCandidateCount());
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
    void rejectsImpossiblePartialCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPackRefreshStatus(ShaderPackRefreshStatus.State.SCANNING, 1, 0));
    }
}
