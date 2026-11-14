package dev.itsdropper.caustiq.shaderpack;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable metadata for a structurally valid shader pack. */
public record ShaderPackDescriptor(
        String id,
        String displayName,
        Path source,
        String shaderRoot,
        ShaderPackSourceType sourceType,
        ShaderPackLayout layout,
        int shaderFileCount,
        int pipelineEntryPointCount,
        long declaredUncompressedBytes
) {
    public ShaderPackDescriptor {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        shaderRoot = Objects.requireNonNull(shaderRoot, "shaderRoot");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        layout = Objects.requireNonNull(layout, "layout");

        if (id.isBlank() || displayName.isBlank()) {
            throw new IllegalArgumentException("Pack identity and display name must not be blank");
        }
        if (shaderRoot.startsWith("/") || shaderRoot.contains("/") || shaderRoot.equals(".") || shaderRoot.equals("..")) {
            throw new IllegalArgumentException("Shader root must be empty or a single safe path segment");
        }
        if (shaderFileCount < 1 || pipelineEntryPointCount < 1 || declaredUncompressedBytes < 0) {
            throw new IllegalArgumentException("Shader-pack counts and sizes are invalid");
        }
    }
}
