package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderRuntimeStatusTrackerTest {
    @Test
    void distinguishesDisabledUnavailableUnselectedAndWaitingStates() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();

        tracker.resetForSelection(false, true, "Lumina");
        assertEquals(ShaderRuntimeStatus.State.DISABLED, tracker.snapshot().state());

        tracker.resetForSelection(true, false, "Lumina");
        assertEquals(ShaderRuntimeStatus.State.UNAVAILABLE, tracker.snapshot().state());

        tracker.resetForSelection(true, true, "");
        assertEquals(ShaderRuntimeStatus.State.NO_PACK, tracker.snapshot().state());

        tracker.resetForSelection(true, true, "Lumina");
        ShaderRuntimeStatus waiting = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.WAITING_FOR_WORLD, waiting.state());
        assertEquals("Lumina", waiting.packName());
        assertTrue(waiting.message().contains("Enter a world"));
    }

    @Test
    void tracksPreparationAndPerFrameProgramProgress() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();
        tracker.preparing("Lumina");
        assertEquals(ShaderRuntimeStatus.State.PREPARING, tracker.snapshot().state());

        tracker.prepared();
        assertEquals(ShaderRuntimeStatus.State.PREPARED, tracker.snapshot().state());

        tracker.discovering("Lumina");
        tracker.beginCompilation(3);
        tracker.programStarted("setup");
        assertEquals("setup", tracker.snapshot().currentProgram());
        tracker.programFinished();
        tracker.programStarted("deferred");
        tracker.programFinished();

        ShaderRuntimeStatus progress = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.COMPILING, progress.state());
        assertEquals(2, progress.completedPrograms());
        assertEquals(3, progress.totalPrograms());
        assertEquals("", progress.currentProgram());

        tracker.programStarted("final");
        tracker.programFinished();
        tracker.complete();

        ShaderRuntimeStatus complete = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.READY, complete.state());
        assertEquals(3, complete.completedPrograms());
        assertTrue(complete.canRetry());
    }

    @Test
    void recordsFailureTypeButNeverCompilerMessageOrShaderSource() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();
        tracker.discovering("Lumina");
        tracker.beginCompilation(1);
        tracker.programStarted("deferred");
        tracker.programFailed("deferred", new IllegalStateException(
                "secret shader source: void main() {} at /home/player/.minecraft/shaderpacks/Lumina"));
        tracker.programFinished();
        tracker.complete();

        ShaderRuntimeStatus status = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.READY_WITH_WARNINGS, status.state());
        assertEquals(1, status.failedProgramCount());
        assertEquals("IllegalStateException", status.failures().getFirst().exceptionType());
        String report = status.createReport();
        assertTrue(report.contains("deferred"));
        assertTrue(report.contains("IllegalStateException"));
        assertTrue(report.contains("latest.log"));
        assertFalse(report.contains("secret shader source"));
        assertFalse(report.contains("void main"));
        assertFalse(report.contains("/home/player"));
        assertTrue(report.contains("No absolute filesystem paths or shader source contents are included."));
    }

    @Test
    void capsFailureDetailsAndKeepsTheExactOmittedCount() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();
        tracker.discovering("Lumina");
        tracker.beginCompilation(40);
        for (int index = 0; index < 40; index++) {
            tracker.programStarted("program_" + index);
            tracker.programFailed("program_" + index, new RuntimeException("private compiler output"));
            tracker.programFinished();
        }
        tracker.complete();

        ShaderRuntimeStatus status = tracker.snapshot();
        assertEquals(40, status.failedProgramCount());
        assertEquals(ShaderRuntimeStatus.MAX_REPORTED_FAILURES, status.failures().size());
        assertEquals(15, status.omittedFailureCount());
        assertTrue(status.createReport().length() <= ShaderRuntimeStatus.MAX_REPORT_CHARACTERS);
        assertTrue(status.createReport().contains("15 additional failure(s) omitted"));
        assertFalse(status.createReport().contains("private compiler output"));
    }

    @Test
    void aNewReloadClearsPreviousFailuresAndProgress() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();
        tracker.discovering("Lumina");
        tracker.beginCompilation(1);
        tracker.programStarted("final");
        tracker.programFailed("final", new RuntimeException("compiler output"));
        tracker.programFinished();
        tracker.complete();
        assertEquals(1, tracker.snapshot().failedProgramCount());

        tracker.resetForSelection(true, true, "Stillwater");
        ShaderRuntimeStatus reset = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.WAITING_FOR_WORLD, reset.state());
        assertEquals("Stillwater", reset.packName());
        assertEquals(0, reset.failedProgramCount());
        assertEquals(0, reset.completedPrograms());
        assertEquals(0, reset.totalPrograms());
    }

    @Test
    void exposesRetryableFailureAndWarningStatesWithSanitizedMessages() {
        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();

        tracker.failed("Unable to read /home/player/.minecraft/shaderpacks/Lumina: Permission denied");
        ShaderRuntimeStatus failed = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.FAILED, failed.state());
        assertTrue(failed.canRetry());
        assertFalse(failed.isBusy());
        assertFalse(failed.message().contains("/home/player"));
        assertTrue(failed.message().contains("[path]"));

        tracker.resetForSelection(true, true, "Lumina");
        tracker.warning("Unsupported feature in /home/player/.minecraft/shaderpacks/Lumina");
        ShaderRuntimeStatus warning = tracker.snapshot();
        assertEquals(ShaderRuntimeStatus.State.READY_WITH_WARNINGS, warning.state());
        assertTrue(warning.canRetry());
        assertFalse(warning.isBusy());
        assertFalse(warning.message().contains("/home/player"));
    }

    @Test
    void validatesProgressAndCopiesFailureCollections() {
        assertThrows(IllegalArgumentException.class, () -> new ShaderRuntimeStatus(
                ShaderRuntimeStatus.State.COMPILING, "Lumina", 2, 1, "", null, 0, ""));

        ShaderRuntimeStatusTracker tracker = new ShaderRuntimeStatusTracker();
        tracker.discovering("Lumina");
        tracker.beginCompilation(1);
        tracker.programStarted("final");
        tracker.programFailed("final", new RuntimeException());
        tracker.programFinished();
        tracker.complete();

        ShaderRuntimeStatus status = tracker.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> status.failures().clear());
    }
}
