package dev.itsdropper.caustiq.shaderpack;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** A candidate that could not safely be accepted as a shader pack. */
public record ShaderPackIssue(Path source, String reason) {
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:[A-Z]:[\\\\/][^:;)]*|\\\\\\\\[^:;)]*|(?<![A-Za-z0-9._-])/[^:;)]*)"
    );
    private static final int MAX_DISPLAY_LENGTH = 240;

    public ShaderPackIssue {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        reason = Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Issue reason must not be blank");
        }
    }

    /** Returns only the candidate's filename, never its game-directory path. */
    public String displayName() {
        Path fileName = source.getFileName();
        return sanitizeDisplayText(fileName == null ? "Shader-pack directory" : fileName.toString());
    }

    /** Returns a bounded, single-line reason with absolute paths removed. */
    public String displayReason() {
        return sanitizeDisplayText(reason.replace(source.toString(), "[path]"));
    }

    public static String sanitizeDisplayText(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown error";
        }
        String sanitized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
        sanitized = ABSOLUTE_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = sanitized.replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (sanitized.isEmpty()) {
            return "Unknown error";
        }
        if (sanitized.length() > MAX_DISPLAY_LENGTH) {
            return sanitized.substring(0, MAX_DISPLAY_LENGTH - 3) + "...";
        }
        return sanitized;
    }
}
