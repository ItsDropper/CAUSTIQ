package dev.itsdropper.caustiq.diagnostics;

import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.IssueDetails;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.IssueKind;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.RejectedCandidate;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void diagnosticsScreenTranslationsCoverRuntimeAndEveryIssueCategory() throws IOException {
        try (InputStream input = CaustiqDiagnosticsTest.class.getResourceAsStream(
                "/assets/caustiq/lang/en_us.json")) {
            assertNotNull(input, "English language resource must be available to tests");
            String translations = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<String> required = List.of(
                    "caustiq.diagnostics.runtime",
                    "caustiq.diagnostics.renderer",
                    "caustiq.diagnostics.renderer.sodium_nvidium",
                    "caustiq.diagnostics.renderer.sodium",
                    "caustiq.diagnostics.renderer.nvidium_without_sodium",
                    "caustiq.diagnostics.renderer.vanilla",
                    "caustiq.diagnostics.guidance.ready",
                    "caustiq.diagnostics.guidance.missing_nvidium",
                    "caustiq.diagnostics.guidance.missing_sodium",
                    "caustiq.diagnostics.guidance.missing_both",
                    "caustiq.diagnostics.shader_state",
                    "caustiq.diagnostics.shaders_enabled",
                    "caustiq.diagnostics.shaders_disabled",
                    "caustiq.diagnostics.shaders_disabled_hint",
                    "caustiq.diagnostics.no_selected_pack_hint",
                    "caustiq.diagnostics.scan_not_completed",
                    "caustiq.diagnostics.scan_cancelling_previous",
                    "caustiq.diagnostics.scan_cancelled_previous",
                    "caustiq.scan.cancel",
                    "caustiq.scan.cancelling",
                    "caustiq.scan.cancelled",
                    "caustiq.diagnostics.scan_summary",
                    "caustiq.diagnostics.rejected_heading",
                    "caustiq.diagnostics.issue_details.title",
                    "caustiq.diagnostics.issue_details.candidate",
                    "caustiq.diagnostics.issue_details.category",
                    "caustiq.diagnostics.issue_details.reason_heading",
                    "caustiq.diagnostics.issue_details.guidance_heading",
                    "caustiq.diagnostics.issue_details.privacy",
                    "caustiq.diagnostics.issue_details.scroll_hint",
                    "caustiq.diagnostics.copy_issue",
                    "caustiq.diagnostics.issue_copied",
                    "caustiq.diagnostics.issue_report"
            );
            for (String key : required) {
                assertTrue(translations.contains("\"" + key + "\""), "Missing translation: " + key);
            }
            for (IssueKind kind : IssueKind.values()) {
                String suffix = kind.name().toLowerCase(java.util.Locale.ROOT);
                assertTrue(translations.contains("\"caustiq.diagnostics.issue_type." + suffix + "\""),
                        "Missing issue type translation: " + suffix);
                assertTrue(translations.contains("\"caustiq.diagnostics.issue_guidance." + suffix + "\""),
                        "Missing issue guidance translation: " + suffix);
            }
        }
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
    void reportsCancelledRefreshWithoutReplacingTheLastCompletedScan() {
        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "1", "1.21.11", "25", "Windows 11", "SODIUM_WITH_NVIDIUM",
                true, true, "SODIUM_AND_NVIDIUM_PRESENT", true, 3, "Lumina",
                true, 5, 2, 1, List.of("Lumina", "Stillwater"),
                List.of(new RejectedCandidate("Broken.zip", "Invalid ZIP")),
                0, 0, "CANCELLED", ""
        ));

        assertTrue(report.contains("2 valid pack(s), 1 rejected candidate(s) across 5 inspected candidate(s)"));
        assertTrue(report.contains("Latest shader-pack refresh: CANCELLED"));
        assertFalse(report.contains("Latest shader-pack refresh: CANCELLED -"));
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
    @Test
    void keepsLargeDiagnosticsReportsWithinTheDocumentedLimit() {
        String repeated = "x".repeat(220);
        List<String> packNames = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "Pack-" + index + "-" + repeated)
                .toList();
        List<RejectedCandidate> rejected = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new RejectedCandidate(
                        "Broken-" + index + ".zip", "Invalid archive: " + repeated))
                .toList();

        String report = CaustiqDiagnostics.format(new CaustiqDiagnostics.Snapshot(
                "1", "1.21.11", "25", "Linux", "SODIUM_WITH_NVIDIUM",
                true, true, "SODIUM_AND_NVIDIUM_PRESENT", true, 3, "Lumina",
                true, 200, 100, 100, packNames, rejected, 0, 0, "FAILED", repeated
        ));

        assertTrue(report.length() <= CaustiqDiagnostics.MAX_REPORT_CHARACTERS);
        assertTrue(report.contains("[Report truncated; additional entries were omitted"));
        assertTrue(report.contains("No absolute game-directory paths or shader source contents are included."));
    }

}
