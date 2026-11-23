package dev.itsdropper.caustiq.diagnostics;

import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.IssueDetails;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.IssueKind;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.RejectedCandidate;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaustiqDiagnosticsTest {
    @Test
    void classifiesScannerRejectionsIntoActionableCategories() {
        Map<String, IssueKind> examples = Map.ofEntries(
                Map.entry("Shader-pack directory must not be a symbolic link.", IssueKind.UNSUPPORTED_LINK),
                Map.entry("Invalid ZIP archive: central directory is missing.", IssueKind.INVALID_ARCHIVE),
                Map.entry("No shaders/ directory was found.", IssueKind.PACK_LAYOUT),
                Map.entry("Multiple shaders/ roots were found; the pack layout is ambiguous.", IssueKind.PACK_LAYOUT),
                Map.entry("No recognized shader pipeline entry points were found under shaders/",
                        IssueKind.PIPELINE_ENTRY_POINTS),
                Map.entry("ZIP archive contains an empty or invalid entry path.", IssueKind.UNSAFE_PATH),
                Map.entry("Directory pack contains an unsafe relative path: ../outside",
                        IssueKind.UNSAFE_PATH),
                Map.entry("ZIP archive exceeds the compressed size limit of 536870912 bytes.",
                        IssueKind.RESOURCE_LIMIT),
                Map.entry("ZIP entry has an unknown compressed size: shaders/large.bin",
                        IssueKind.RESOURCE_LIMIT),
                Map.entry("Directory pack exceeds the maximum nesting depth of 16.",
                        IssueKind.RESOURCE_LIMIT),
                Map.entry("Directory pack contains more than 10000 entries.", IssueKind.RESOURCE_LIMIT),
                Map.entry("Unable to list shader-pack directory: Permission denied", IssueKind.ACCESS),
                Map.entry("Shader-pack directory does not exist or is not a directory.", IssueKind.ACCESS),
                Map.entry("Candidate is not a regular directory or ZIP archive.", IssueKind.INVALID_SOURCE),
                Map.entry("Unexpected scanner failure.", IssueKind.OTHER)
        );

        examples.forEach((reason, expected) ->
                assertEquals(expected, CaustiqDiagnostics.classifyIssue(reason), reason));
        assertEquals(IssueKind.OTHER, CaustiqDiagnostics.classifyIssue(null));
    }

    @Test
    void issueDetailsExposeOnlySanitizedCandidateIdentityAndReason() {
        Path source = Path.of("/home/player/.minecraft/shaderpacks/Broken.zip");
        ShaderPackIssue issue = new ShaderPackIssue(source,
                "Unable to read " + source + ": Invalid ZIP archive");

        IssueDetails details = CaustiqDiagnostics.describeIssue(issue);

        assertEquals("Broken.zip", details.candidateName());
        assertEquals(IssueKind.INVALID_ARCHIVE, details.kind());
        assertTrue(details.reason().contains("[path]"));
        assertFalse(details.reason().contains("/home/player"));
        assertFalse(details.reason().contains(".minecraft"));
    }

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
