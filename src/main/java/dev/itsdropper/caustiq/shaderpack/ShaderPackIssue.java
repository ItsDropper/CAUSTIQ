package dev.itsdropper.caustiq.shaderpack;

import java.nio.file.Path;
import java.util.Objects;

/** A candidate that could not safely be accepted as a shader pack. */
public record ShaderPackIssue(Path source, String reason) {
    public ShaderPackIssue {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        reason = Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Issue reason must not be blank");
        }
    }
}
