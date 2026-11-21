package dev.itsdropper.caustiq.shaderpack;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure filtering and ordering rules for the in-game shader-pack browser. */
public final class ShaderPackBrowserModel {
    private static final Comparator<ShaderPackDescriptor> BY_NAME =
            Comparator.comparing(ShaderPackDescriptor::displayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ShaderPackDescriptor::id);

    private ShaderPackBrowserModel() { }

    public static List<ShaderPackDescriptor> apply(
            List<ShaderPackDescriptor> packs,
            String query,
            SourceFilter sourceFilter,
            SortOrder sortOrder
    ) {
        Objects.requireNonNull(packs, "packs");
        Objects.requireNonNull(sourceFilter, "sourceFilter");
        Objects.requireNonNull(sortOrder, "sortOrder");

        Comparator<ShaderPackDescriptor> order = switch (sortOrder) {
            case NAME -> BY_NAME;
            case MOST_SHADERS -> Comparator
                    .comparingInt(ShaderPackDescriptor::shaderFileCount).reversed()
                    .thenComparing(BY_NAME);
            case LARGEST -> Comparator
                    .comparingLong(ShaderPackDescriptor::declaredUncompressedBytes).reversed()
                    .thenComparing(BY_NAME);
        };

        return ShaderPackFilter.filter(packs, query).stream()
                .filter(pack -> switch (sourceFilter) {
                    case ALL -> true;
                    case FOLDERS -> pack.sourceType() == ShaderPackSourceType.DIRECTORY;
                    case ZIP_ARCHIVES -> pack.sourceType() == ShaderPackSourceType.ZIP_ARCHIVE;
                })
                .sorted(order)
                .toList();
    }

    public enum SourceFilter {
        ALL,
        FOLDERS,
        ZIP_ARCHIVES;

        public SourceFilter next() {
            return switch (this) {
                case ALL -> FOLDERS;
                case FOLDERS -> ZIP_ARCHIVES;
                case ZIP_ARCHIVES -> ALL;
            };
        }
    }

    public enum SortOrder {
        NAME,
        MOST_SHADERS,
        LARGEST;

        public SortOrder next() {
            return switch (this) {
                case NAME -> MOST_SHADERS;
                case MOST_SHADERS -> LARGEST;
                case LARGEST -> NAME;
            };
        }
    }
}
