package dev.itsdropper.caustiq.shaderpack;

/** Immutable user-facing state for shader-pack discovery. */
public record ShaderPackRefreshStatus(
        State state,
        int packCount,
        int rejectedCandidateCount,
        String failureMessage,
        ShaderPackScanProgress progress
) {
    public ShaderPackRefreshStatus(State state, int packCount, int rejectedCandidateCount) {
        this(state, packCount, rejectedCandidateCount, "", null);
    }

    public ShaderPackRefreshStatus(
            State state,
            int packCount,
            int rejectedCandidateCount,
            String failureMessage
    ) {
        this(state, packCount, rejectedCandidateCount, failureMessage, null);
    }

    public ShaderPackRefreshStatus {
        if (state == null) {
            throw new IllegalArgumentException("Refresh state must not be null");
        }
        if (packCount < 0 || rejectedCandidateCount < 0) {
            throw new IllegalArgumentException("Refresh counts must not be negative");
        }
        failureMessage = failureMessage == null || failureMessage.isBlank()
                ? ""
                : ShaderPackIssue.sanitizeDisplayText(failureMessage);
        if (state != State.COMPLETE && (packCount != 0 || rejectedCandidateCount != 0)) {
            throw new IllegalArgumentException("Only a completed scan can contain result counts");
        }
        if (state != State.FAILED && !failureMessage.isEmpty()) {
            throw new IllegalArgumentException("Only a failed scan can contain a failure message");
        }
        if (state == State.SCANNING) {
            progress = progress == null ? ShaderPackScanProgress.discoveringCandidates() : progress;
        } else if (progress != null) {
            throw new IllegalArgumentException("Only a scanning refresh can contain progress");
        }
    }

    public static ShaderPackRefreshStatus idle() {
        return new ShaderPackRefreshStatus(State.IDLE, 0, 0);
    }

    public static ShaderPackRefreshStatus scanning() {
        return scanning(ShaderPackScanProgress.discoveringCandidates());
    }

    public static ShaderPackRefreshStatus scanning(ShaderPackScanProgress progress) {
        return new ShaderPackRefreshStatus(State.SCANNING, 0, 0, "", progress);
    }

    public static ShaderPackRefreshStatus complete(ShaderPackScanResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Completed scan result must not be null");
        }
        return new ShaderPackRefreshStatus(State.COMPLETE, result.packs().size(), result.issues().size());
    }

    public static ShaderPackRefreshStatus failed() {
        return failed("");
    }

    public static ShaderPackRefreshStatus failed(String message) {
        return new ShaderPackRefreshStatus(State.FAILED, 0, 0, message);
    }

    public boolean isScanning() {
        return state == State.SCANNING;
    }

    public double progressFraction() {
        return progress == null ? 0.0 : progress.fraction();
    }

    public boolean isDeterminate() {
        return progress != null && progress.isDeterminate();
    }

    public enum State {
        IDLE,
        SCANNING,
        COMPLETE,
        FAILED
    }
}
