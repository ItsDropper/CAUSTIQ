package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, path-safe structural inventory for a discovered shader pack.
 * Shader source contents are never retained or included in the copied report.
 */
public record ShaderPackInspection(
        ShaderPackDescriptor pack,
        List<String> files,
        int metadataOptionCount,
        String metadataFailure
) {
    private static final int MAX_REPORTED_FILES = 200;

    public ShaderPackInspection {
        pack = Objects.requireNonNull(pack, "pack");
        files = List.copyOf(files);
        if (metadataOptionCount < -1) {
            throw new IllegalArgumentException("Metadata option count must be -1 (unavailable) or non-negative");
        }
        metadataFailure = metadataFailure == null || metadataFailure.isBlank()
                ? "" : ShaderPackIssue.sanitizeDisplayText(metadataFailure);
    }

    /**
     * Inspects the pack's shader-relative inventory and option metadata.
     * Metadata failures do not discard an otherwise readable file inventory.
     */
    public static ShaderPackInspection inspect(ShaderPackDescriptor pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        try (ShaderPackSources sources = ShaderPackSources.open(pack)) {
            List<String> files = sources.listShaderFiles();
            int optionCount = -1;
            String metadataFailure = "";
            try {
                String properties = files.contains("shaders.properties")
                        ? sources.read("shaders.properties") : "";
                optionCount = ShaderPackMetadata.parse(properties, sources).options().size();
            } catch (IOException | IllegalArgumentException exception) {
                metadataFailure = ShaderPackIssue.sanitizeDisplayText(exception.getMessage());
            }
            return new ShaderPackInspection(pack, files, optionCount, metadataFailure);
        }
    }

    /** Creates a bounded support report containing relative filenames, never absolute paths or shader source. */
    public String createReport() {
        String sourceName = pack.source().getFileName() == null
                ? "unknown" : pack.source().getFileName().toString();
        StringBuilder report = new StringBuilder(1_024);
        report.append("CAUSTIQ Shader Pack Inspection\n")
                .append("Name: ").append(ShaderPackIssue.sanitizeDisplayText(pack.displayName())).append('\n')
                .append("Pack ID: ").append(ShaderPackIssue.sanitizeDisplayText(pack.id())).append('\n')
                .append("Source: ").append(pack.sourceType().name().replace('_', ' '))
                .append(" (").append(ShaderPackIssue.sanitizeDisplayText(sourceName)).append(")\n")
                .append("Layout: ").append(pack.layout().name().replace('_', ' ')).append('\n')
                .append("Shader source files: ").append(pack.shaderFileCount()).append('\n')
                .append("Pipeline entry points: ").append(pack.pipelineEntryPointCount()).append('\n')
                .append("Declared uncompressed size: ").append(pack.declaredUncompressedBytes()).append(" bytes\n");
        if (metadataOptionCount >= 0) {
            report.append("Pack options: ").append(metadataOptionCount).append('\n');
        } else {
            report.append("Pack options: unavailable");
            if (!metadataFailure.isBlank()) report.append(" - ").append(metadataFailure);
            report.append('\n');
        }
        report.append("Files under shaders/: ").append(files.size()).append('\n');
        int reported = Math.min(MAX_REPORTED_FILES, files.size());
        for (int index = 0; index < reported; index++) {
            report.append("- shaders/")
                    .append(ShaderPackIssue.sanitizeDisplayText(files.get(index)))
                    .append('\n');
        }
        if (files.size() > reported) {
            report.append("- ... ").append(files.size() - reported)
                    .append(" additional file(s) omitted\n");
        }
        report.append("\nNo absolute game-directory paths or shader source contents are included.");
        return report.toString();
    }
}
