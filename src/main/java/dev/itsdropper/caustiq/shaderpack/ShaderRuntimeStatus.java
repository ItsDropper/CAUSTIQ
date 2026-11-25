package dev.itsdropper.caustiq.shaderpack;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, privacy-conscious view of shader-pack preparation and program processing.
 * It deliberately excludes compiler messages and source text; the latest log retains those details.
 */
public record ShaderRuntimeStatus(
        State state,
        String packName,
        int completedPrograms,
        int totalPrograms,
        String currentProgram,
        List<ProgramFailure> failures,
        int omittedFailureCount,
        String message
) {
    public static final int MAX_REPORTED_FAILURES = 25;
    public static final int MAX_REPORT_CHARACTERS = 16_384;
    private static final String PRIVACY_NOTICE =
            "\nNo absolute filesystem paths or shader source contents are included. "
                    + "Compiler output is intentionally omitted; see latest.log for full details.";
    private static final String TRUNCATION_NOTICE =
            "\n[Report truncated; additional failures were omitted to keep it within 16,384 characters.]";

    public ShaderRuntimeStatus {
        state = Objects.requireNonNull(state, "state");
        if (completedPrograms < 0 || totalPrograms < 0 || completedPrograms > totalPrograms) {
            throw new IllegalArgumentException("Shader program progress must be within the declared total");
        }
        if (omittedFailureCount < 0) {
            throw new IllegalArgumentException("Omitted failure count must not be negative");
        }
        packName = safeLabel(packName, "None");
        currentProgram = currentProgram == null || currentProgram.isBlank()
                ? "" : ShaderPackIssue.sanitizeDisplayText(currentProgram);
        List<ProgramFailure> suppliedFailures = failures == null ? List.of() : List.copyOf(failures);
        int overflow = Math.max(0, suppliedFailures.size() - MAX_REPORTED_FAILURES);
        failures = List.copyOf(suppliedFailures.subList(
                0, Math.min(MAX_REPORTED_FAILURES, suppliedFailures.size())));
        omittedFailureCount += overflow;
        message = message == null || message.isBlank()
                ? "" : ShaderPackIssue.sanitizeDisplayText(message);
    }

    public int failedProgramCount() {
        return failures.size() + omittedFailureCount;
    }

    /** Returns a normalized fraction without inventing progress when no programs were discovered. */
    public double progressFraction() {
        return totalPrograms == 0 ? 0.0 : completedPrograms / (double) totalPrograms;
    }

    public boolean isBusy() {
        return state == State.PREPARING || state == State.DISCOVERING || state == State.COMPILING;
    }

    public boolean canRetry() {
        return state == State.FAILED || state == State.READY_WITH_WARNINGS || state == State.READY;
    }

    /** Creates a bounded report without exception messages, stack traces, absolute paths or shader source. */
    public String createReport() {
        StringBuilder report = new StringBuilder(768);
        report.append("CAUSTIQ Shader Runtime Status\n")
                .append("State: ").append(state.name()).append('\n')
                .append("Selected pack: ").append(packName).append('\n')
                .append("Programs processed: ").append(completedPrograms)
                .append(" / ").append(totalPrograms).append('\n');
        if (!currentProgram.isBlank()) {
            report.append("Current program: ").append(currentProgram).append('\n');
        }
        if (!message.isBlank()) {
            report.append("Status: ").append(message).append('\n');
        }
        if (failedProgramCount() == 0) {
            report.append("Program failures: none\n");
        } else {
            report.append("Program failures: ").append(failedProgramCount()).append('\n');
            failures.forEach(failure -> report.append("- ")
                    .append(failure.programName()).append(": ")
                    .append(failure.exceptionType()).append('\n'));
            if (omittedFailureCount > 0) {
                report.append("- ... ").append(omittedFailureCount)
                        .append(" additional failure(s) omitted\n");
            }
        }

        String body = report.toString();
        String complete = body + PRIVACY_NOTICE;
        if (complete.length() <= MAX_REPORT_CHARACTERS) return complete;
        int bodyLimit = Math.max(0,
                MAX_REPORT_CHARACTERS - PRIVACY_NOTICE.length() - TRUNCATION_NOTICE.length());
        return body.substring(0, Math.min(body.length(), bodyLimit)).stripTrailing()
                + TRUNCATION_NOTICE + PRIVACY_NOTICE;
    }

    private static String safeLabel(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback : ShaderPackIssue.sanitizeDisplayText(value);
    }

    public enum State {
        DISABLED,
        UNAVAILABLE,
        NO_PACK,
        WAITING_FOR_WORLD,
        PREPARING,
        PREPARED,
        DISCOVERING,
        COMPILING,
        READY,
        READY_WITH_WARNINGS,
        FAILED
    }

    public record ProgramFailure(String programName, String exceptionType) {
        public ProgramFailure {
            programName = safeLabel(programName, "Unknown program");
            exceptionType = safeLabel(exceptionType, "Unknown failure");
        }
    }
}
