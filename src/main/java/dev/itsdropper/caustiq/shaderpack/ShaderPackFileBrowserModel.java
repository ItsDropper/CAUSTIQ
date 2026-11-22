package dev.itsdropper.caustiq.shaderpack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure search and file-kind filtering rules for the shader-pack inspector. */
public final class ShaderPackFileBrowserModel {
    private static final Set<String> SHADER_EXTENSIONS =
            Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes", "glsl");
    private static final Comparator<String> PATH_ORDER =
            Comparator.comparing((String path) -> path, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Comparator.naturalOrder());

    private ShaderPackFileBrowserModel() { }

    public static List<String> apply(List<String> files, String query, FileFilter fileFilter) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(fileFilter, "fileFilter");
        String[] terms = query == null || query.isBlank()
                ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\\s+");

        return files.stream()
                .map(path -> Objects.requireNonNull(path, "file path"))
                .filter(path -> matchesFilter(path, fileFilter))
                .filter(path -> matchesQuery(path, terms))
                .sorted(PATH_ORDER)
                .toList();
    }

    private static boolean matchesQuery(String path, String[] terms) {
        if (terms.length == 0) return true;
        String normalized = path.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!normalized.contains(term)) return false;
        }
        return true;
    }

    private static boolean matchesFilter(String path, FileFilter fileFilter) {
        String extension = extension(path);
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
