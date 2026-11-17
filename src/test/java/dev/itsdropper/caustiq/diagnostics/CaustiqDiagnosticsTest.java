package dev.itsdropper.caustiq.diagnostics;

import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.RejectedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaustiqDiagnosticsTest {
    @Test
    void includesRendererAndShaderPackStateInReport() {
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "0.1.0-nvidium-dev", "1.21.11", "25.0.1", "Windows 11",
                "SODIUM_WITH_NVIDIUM", true, true, "SODIUM_AND_NVIDIUM_PRESENT",
                true, 3, "Lumina", true, 4, 2, 2, List.of("Lumina", "Stillwater"),
                List.of(), 0, 0, "COMPLETE", ""
        ));

        assertTrue(report.contains("Renderer stack: SODIUM_WITH_NVIDIUM"));
        assertTrue(report.contains("Nvidium dependency check: SODIUM_AND_NVIDIUM_PRESENT"));
        assertTrue(report.contains("Compile budget: 3 programs/frame"));
        assertTrue(report.contains("Selected shader pack: Lumina"));
        assertTrue(report.contains("2 valid pack(s), 2 rejected candidate(s) across 4 inspected candidate(s)"));
        assertTrue(report.contains("- Stillwater"));
    }

    @Test
    void reportsAnUnfinishedScanWithoutInventingPackResults() {
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "unknown", "1.21.11", "21", "Linux", "VANILLA",
                false, false, "MISSING_SODIUM", false, 1, "Not available",
                false, 0, 0, 0, List.of(), List.of(), 0, 0, "IDLE", ""
        ));

        assertTrue(report.contains("Shader-pack scan: not completed"));
        assertFalse(report.contains("0 valid pack(s)"));
    }

    @Test
    void includesSanitizedRejectionDetailsWithoutAbsolutePaths() {
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "1", "1.21.11", "21", "Linux", "VANILLA",
                false, false, "MISSING_SODIUM", false, 1, "None",
                true, 2, 1, 1, List.of("Example"),
                List.of(new RejectedCandidate("Broken.zip",
                        "Unable to read /home/player/.minecraft/shaderpacks/Broken.zip: invalid ZIP")),
                0, 0, "FAILED",
                "Unable to read /home/player/.minecraft/shaderpacks: Permission denied"
        ));

        assertTrue(report.contains("Latest shader-pack refresh: FAILED - Unable to read [path]: Permission denied"));
        assertTrue(report.contains("Rejected candidates (candidate names and reasons; full paths omitted)"));
        assertTrue(report.contains("- Broken.zip: Unable to read [path]: invalid ZIP"));
        assertFalse(report.contains("/home/player"));
        assertFalse(report.contains(".minecraft"));
        assertTrue(report.contains("No absolute game-directory paths or shader source contents"));
    }

    @Test
    void reportsOmittedEntriesWhenTheReportLimitIsReached() {
        List<RejectedCandidate> candidates = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> new RejectedCandidate("Broken-" + index + ".zip", "Invalid ZIP"))
                .toList();
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "1", "1.21.11", "21", "Linux", "VANILLA",
                false, false, "MISSING_SODIUM", false, 1, "None",
                true, 30, 0, 30, List.of(), candidates, 0, 5, "COMPLETE", ""
        ));

        assertTrue(report.contains("5 additional rejection(s) omitted"));
    }
}
