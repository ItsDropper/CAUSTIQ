package dev.itsdropper.caustiq.shaderpack;

/** Immutable user-facing state for shader-pack discovery. */
public record ShaderPackRefreshStatus(State state, int packCount, int rejectedCandidateCount) {
    public ShaderPackRefreshStatus {
        if (state == null) {
            throw new IllegalArgumentException("Refresh state must not be null");
        }
        if (packCount < 0 || rejectedCandidateCount < 0) {
            throw new IllegalArgumentException("Refresh counts must not be negative");
        }
        if (state != State.COMPLETE && (packCount != 0 || rejectedCandidateCount != 0)) {
            throw new IllegalArgumentException("Only a completed scan can contain result counts");
        }
    }

    public static ShaderPackRefreshStatus idle() {
        return new ShaderPackRefreshStatus(State.IDLE, 0, 0);
    }

    public static ShaderPackRefreshStatus scanning() {
        return new ShaderPackRefreshStatus(State.SCANNING, 0, 0);
    }

    public static ShaderPackRefreshStatus complete(ShaderPackScanResult result) {
        return new ShaderPackRefreshStatus(State.COMPLETE, result.packs().size(), result.issues().size());
    }

    public static ShaderPackRefreshStatus failed() {
        return new ShaderPackRefreshStatus(State.FAILED, 0, 0);
    }

    public boolean isScanning() {
        return state == State.SCANNING;
    }

    public enum State {
        IDLE,
        SCANNING,
        COMPLETE,
        FAILED
    }
}
