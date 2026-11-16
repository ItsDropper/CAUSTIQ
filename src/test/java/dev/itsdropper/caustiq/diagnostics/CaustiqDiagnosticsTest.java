package dev.itsdropper.caustiq.diagnostics;

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
                true, 3, "Lumina", true, 4, 2, 2, List.of("Lumina", "Stillwater")
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
                false, 0, 0, 0, List.of()
        ));

        assertTrue(report.contains("Shader-pack scan: not completed"));
        assertFalse(report.contains("0 valid pack(s)"));
    }

    @Test
    void explicitlyDocumentsPrivacyBoundary() {
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "1", "1.21.11", "21", "Linux", "VANILLA",
                false, false, "MISSING_SODIUM", false, 1, "None",
                true, 0, 0, 0, List.of()
        ));

        assertTrue(report.contains("No game-directory paths, usernames, or shader source contents"));
        assertFalse(report.contains("C:\\Users\\"));
    }
}
