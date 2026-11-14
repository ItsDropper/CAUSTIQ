package dev.itsdropper.caustiq.shaderpack;

import java.util.Objects;

/** One compiler diagnostic mapped back to a shader-pack source file when the driver provides source IDs. */
public record ShaderDiagnostic(
        Severity severity,
        int sourceId,
        int line,
        int column,
        String sourcePath,
        String message
) {
    public enum Severity { ERROR, WARNING }

    public ShaderDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (sourceId < -1 || line < 0 || column < 0 || message.isBlank()) {
            throw new IllegalArgumentException("Invalid shader diagnostic location or message");
        }
    }
}
