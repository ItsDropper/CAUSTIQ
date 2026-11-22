package dev.itsdropper.caustiq.shaderpack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pre-indexed, immutable search and file-kind filtering rules for the shader-pack inspector. */
public final class ShaderPackFileBrowserModel {
    private static final Set<String> SHADER_EXTENSIONS =
            Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes", "glsl");
    private static final Comparator<FileEntry> PATH_ORDER =
            Comparator.comparing(FileEntry::path, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(FileEntry::path);

    private final List<FileEntry> files;

    public ShaderPackFileBrowserModel(List<String> files) {
        Objects.requireNonNull(files, "files");
        this.files = files.stream()
                .map(path -> {
                    String requiredPath = Objects.requireNonNull(path, "file path");
                    return new FileEntry(
                            requiredPath,
                            requiredPath.toLowerCase(Locale.ROOT),
                            extension(requiredPath));
                })
                .sorted(PATH_ORDER)
                .toList();
    }

    /** Filters the pre-sorted, pre-normalized inventory without re-sorting or re-normalizing paths. */
    public List<String> apply(String query, FileFilter fileFilter) {
        Objects.requireNonNull(fileFilter, "fileFilter");
        String[] terms = query == null || query.isBlank()
                ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\\s+");

        return files.stream()
                .filter(file -> matchesFilter(file.extension(), fileFilter))
                .filter(file -> matchesQuery(file.normalizedPath(), terms))
                .map(FileEntry::path)
                .toList();
    }

    private static boolean matchesQuery(String normalizedPath, String[] terms) {
        if (terms.length == 0) return true;
        for (String term : terms) {
            if (!normalizedPath.contains(term)) return false;
        }
        return true;
    }

    private static boolean matchesFilter(String extension, FileFilter fileFilter) {
        boolean shaderSource = SHADER_EXTENSIONS.contains(extension);
        boolean properties = extension.equals("properties");
        return switch (fileFilter) {
            case ALL -> true;
            case SHADER_SOURCES -> shaderSource;
            case PROPERTIES -> properties;
            case ASSETS -> !shaderSource && !properties;
        };
    }

    private static String extension(String path) {
        int separator = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= separator || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record FileEntry(String path, String normalizedPath, String extension) { }

    public enum FileFilter {
        ALL,
        SHADER_SOURCES,
        PROPERTIES,
        ASSETS;

        public FileFilter next() {
            return switch (this) {
                case ALL -> SHADER_SOURCES;
                case SHADER_SOURCES -> PROPERTIES;
                case PROPERTIES -> ASSETS;
                case ASSETS -> ALL;
            };
        }
    }
}
