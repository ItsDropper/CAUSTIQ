package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe, bounded status accumulator for the existing shader preparation lifecycle. */
public final class ShaderRuntimeStatusTracker {
    private final List<ShaderRuntimeStatus.ProgramFailure> failures = new ArrayList<>();
    private ShaderRuntimeStatus.State state = ShaderRuntimeStatus.State.DISABLED;
    private String packName = "None";
    private int completedPrograms;
    private int totalPrograms;
    private int omittedFailureCount;
    private String currentProgram = "";
    private String message = "Caustiq shaders are disabled.";

    public synchronized void resetForSelection(boolean enabled, boolean nvidiumAvailable, String selectedPackName) {
        clearProgress();
        packName = safePackName(selectedPackName);
        if (!enabled) {
            state = ShaderRuntimeStatus.State.DISABLED;
            message = "Enable Caustiq shaders to prepare the selected pack.";
        } else if (!nvidiumAvailable) {
            state = ShaderRuntimeStatus.State.UNAVAILABLE;
            message = "The required Nvidium integration is unavailable. Check the installed renderer versions.";
        } else if (selectedPackName == null || selectedPackName.isBlank()) {
            state = ShaderRuntimeStatus.State.NO_PACK;
            message = "Select a shader pack in Browse packs.";
        } else {
            state = ShaderRuntimeStatus.State.WAITING_FOR_WORLD;
            message = "Enter a world to prepare the selected shader pack.";
        }
    }

    public synchronized void preparing(String selectedPackName) {
        resetProgress();
        packName = safePackName(selectedPackName);
        state = ShaderRuntimeStatus.State.PREPARING;
        message = "Preparing the selected shader pack.";
    }

    public synchronized void prepared() {
        state = ShaderRuntimeStatus.State.PREPARED;
        currentProgram = "";
        message = "Pack preparation finished; enter a world to process its shader programs.";
    }

    public synchronized void discovering(String selectedPackName) {
        resetProgress();
        packName = safePackName(selectedPackName);
        state = ShaderRuntimeStatus.State.DISCOVERING;
        message = "Reading shader programs and building the pass plan.";
    }

    public synchronized void beginCompilation(int programCount) {
        totalPrograms = Math.max(0, programCount);
        completedPrograms = 0;
        currentProgram = "";
        state = ShaderRuntimeStatus.State.COMPILING;
        message = "Processing shader programs within the configured per-frame budget.";
    }

    public synchronized void programStarted(String programName) {
        currentProgram = safePackName(programName);
    }

    public synchronized void programFailed(String programName, Throwable failure) {
        String exceptionType = failure == null ? "Unknown failure" : failure.getClass().getSimpleName();
        if (failures.size() < ShaderRuntimeStatus.MAX_REPORTED_FAILURES) {
            failures.add(new ShaderRuntimeStatus.ProgramFailure(programName, exceptionType));
        } else {
            omittedFailureCount++;
        }
    }

    public synchronized void programFinished() {
        completedPrograms = Math.min(totalPrograms, completedPrograms + 1);
        currentProgram = "";
    }

    public synchronized void complete() {
        completedPrograms = totalPrograms;
        currentProgram = "";
        if (failures.isEmpty() && omittedFailureCount == 0) {
            state = ShaderRuntimeStatus.State.READY;
            message = "Shader-pack preparation and program processing completed.";
        } else {
            state = ShaderRuntimeStatus.State.READY_WITH_WARNINGS;
            message = "Some shader programs failed to compile. Open latest.log for compiler details.";
        }
    }

    public synchronized void failed(String safeMessage) {
        state = ShaderRuntimeStatus.State.FAILED;
        currentProgram = "";
        message = ShaderPackIssue.sanitizeDisplayText(safeMessage);
    }

    public synchronized void warning(String safeMessage) {
        state = ShaderRuntimeStatus.State.READY_WITH_WARNINGS;
        currentProgram = "";
        message = ShaderPackIssue.sanitizeDisplayText(safeMessage);
    }

    public synchronized ShaderRuntimeStatus snapshot() {
        return new ShaderRuntimeStatus(
                state, packName, completedPrograms, totalPrograms, currentProgram,
                failures, omittedFailureCount, message);
    }

    private void resetProgress() {
        failures.clear();
        completedPrograms = 0;
        totalPrograms = 0;
        omittedFailureCount = 0;
        currentProgram = "";
        message = "";
    }

    private void clearProgress() {
        resetProgress();
    }

    private static String safePackName(String value) {
        return value == null || value.isBlank()
                ? "None" : ShaderPackIssue.sanitizeDisplayText(value);
    }
}
