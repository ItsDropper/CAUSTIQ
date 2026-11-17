package dev.itsdropper.caustiq.shaderpack;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Case-insensitive, token-based search over the metadata shown for discovered packs. */
public final class ShaderPackFilter {
    private ShaderPackFilter() { }

    /**
     * Returns packs matching every query token. Matching is limited to the pack's
     * display name, stable ID, source filename and source type; file contents are
     * never opened or searched.
     */
    public static List<ShaderPackDescriptor> filter(List<ShaderPackDescriptor> packs, String query) {
        Objects.requireNonNull(packs, "packs");
        if (query == null || query.isBlank()) return List.copyOf(packs);
        String[] tokens = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        return packs.stream().filter(pack -> matches(pack, tokens)).toList();
    }

    private static boolean matches(ShaderPackDescriptor pack, String[] tokens) {
        String sourceName = pack.source().getFileName() == null ? "" : pack.source().getFileName().toString();
        String sourceType = pack.sourceType() == ShaderPackSourceType.ZIP_ARCHIVE ? "zip archive" : "folder directory";
        String searchable = (pack.displayName() + " " + pack.id() + " " + sourceName + " " + sourceType)
                .toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!searchable.contains(token)) return false;
        }
        return true;
    }
}
