package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderRuntimeStatusTest {
    @Test
    void progressFractionReflectsCompletedProgramsAndHandlesEmptyWork() {
        assertEquals(0.0, status(0, 0).progressFraction(), 0.000001);
        assertEquals(0.0, status(0, 4).progressFraction(), 0.000001);
        assertEquals(0.5, status(2, 4).progressFraction(), 0.000001);
        assertEquals(1.0, status(4, 4).progressFraction(), 0.000001);
    }

    @Test
    void runtimeStatusScreenTranslationsCoverEveryStateAndAction() throws IOException {
        try (InputStream input = ShaderRuntimeStatusTest.class.getResourceAsStream(
                "/assets/caustiq/lang/en_us.json")) {
            assertTrue(input != null, "English language resource must be available to tests");
            String translations = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (ShaderRuntimeStatus.State state : ShaderRuntimeStatus.State.values()) {
                String suffix = state.name().toLowerCase(java.util.Locale.ROOT);
                assertTrue(translations.contains("\"caustiq.runtime_status.state." + suffix + "\""),
                        "Missing runtime state translation: " + suffix);
                assertTrue(translations.contains("\"caustiq.runtime_status.guidance." + suffix + "\""),
                        "Missing runtime guidance translation: " + suffix);
            }
            List<String> required = List.of(
                    "caustiq.settings.runtime_status",
                    "caustiq.diagnostics.runtime_status",
                    "caustiq.runtime_status.title",
                    "caustiq.runtime_status.copy",
                    "caustiq.runtime_status.copied",
                    "caustiq.runtime_status.reload",
                    "caustiq.runtime_status.reload_requested",
                    "caustiq.runtime_status.working",
                    "caustiq.runtime_status.back",
                    "caustiq.runtime_status.scroll_hint",
                    "caustiq.runtime_status.pack",
                    "caustiq.runtime_status.progress",
                    "caustiq.runtime_status.current_program",
                    "caustiq.runtime_status.details_heading",
                    "caustiq.runtime_status.failures",
                    "caustiq.runtime_status.failure_entry",
                    "caustiq.runtime_status.omitted",
                    "caustiq.runtime_status.no_failures",
                    "caustiq.runtime_status.privacy"
            );
            for (String key : required) {
                assertTrue(translations.contains("\"" + key + "\""), "Missing translation: " + key);
            }
        }
    }

    private static ShaderRuntimeStatus status(int completed, int total) {
        return new ShaderRuntimeStatus(
                ShaderRuntimeStatus.State.COMPILING,
                "Lumina",
                completed,
                total,
                "",
                List.of(),
                0,
                "");
    }
}
