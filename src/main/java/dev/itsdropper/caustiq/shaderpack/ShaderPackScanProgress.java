package dev.itsdropper.caustiq.shaderpack;

/** Immutable progress for one shader-pack discovery scan. Candidate labels never contain parent paths. */
public record ShaderPackScanProgress(
        Stage stage,
        int completedCandidates,
        int totalCandidates,
        String currentCandidate
) {
    private static final int MAX_CANDIDATE_LABEL_LENGTH = 48;

    public ShaderPackScanProgress {
        if (stage == null) {
            throw new IllegalArgumentException("Scan progress stage must not be null");
        }
        if (completedCandidates < 0 || totalCandidates < 0 || completedCandidates > totalCandidates) {
            throw new IllegalArgumentException("Scan progress must be within the declared candidate total");
        }

        String candidate = currentCandidate == null ? "" : currentCandidate;
        int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf((char) 92));
        if (separator >= 0) {
            candidate = candidate.substring(separator + 1);
        }
        currentCandidate = candidate.isBlank() ? "" : ShaderPackIssue.sanitizeDisplayText(candidate);
        if (currentCandidate.length() > MAX_CANDIDATE_LABEL_LENGTH) {
            currentCandidate = currentCandidate.substring(0, MAX_CANDIDATE_LABEL_LENGTH - 3) + "...";
        }

        if (stage == Stage.DISCOVERING_CANDIDATES
                && (completedCandidates != 0 || totalCandidates != 0 || !currentCandidate.isEmpty())) {
            throw new IllegalArgumentException("Candidate discovery progress cannot report inspected candidates");
        }
        if (!currentCandidate.isEmpty() && completedCandidates >= totalCandidates) {
            throw new IllegalArgumentException("A current candidate must remain in the uninspected portion");
        }
    }

    public static ShaderPackScanProgress discoveringCandidates() {
        return new ShaderPackScanProgress(Stage.DISCOVERING_CANDIDATES, 0, 0, "");
    }

    public static ShaderPackScanProgress inspectingCandidates(
            int completedCandidates,
            int totalCandidates,
            String currentCandidate
    ) {
        return new ShaderPackScanProgress(
                Stage.INSPECTING_CANDIDATES, completedCandidates, totalCandidates, currentCandidate);
    }

    public boolean isDeterminate() {
        return stage == Stage.INSPECTING_CANDIDATES && totalCandidates > 0;
    }

    public double fraction() {
        return totalCandidates == 0 ? 0.0 : completedCandidates / (double) totalCandidates;
    }

    public enum Stage {
        DISCOVERING_CANDIDATES,
        INSPECTING_CANDIDATES
    }
}
