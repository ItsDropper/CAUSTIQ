package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.shaderpack.ShaderPackBrowserModel.SortOrder;
import dev.itsdropper.caustiq.shaderpack.ShaderPackBrowserModel.SourceFilter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderPackBrowserModelTest {
    private static ShaderPackDescriptor pack(
            String id,
            String name,
            String source,
            ShaderPackSourceType type,
            int shaderFiles,
            long size
    ) {
        return new ShaderPackDescriptor(id, name, Path.of(source), "",
                type, ShaderPackLayout.OPTIFINE_STYLE, shaderFiles, 2, size);
    }

    private final List<ShaderPackDescriptor> packs = List.of(
            pack("complementary", "Complementary", "/packs/Complementary.zip",
                    ShaderPackSourceType.ZIP_ARCHIVE, 20, 1_048_576),
            pack("photon", "Photon", "/packs/Photon",
                    ShaderPackSourceType.DIRECTORY, 12, 524_288),
            pack("stillwater", "Stillwater", "/packs/Stillwater.zip",
                    ShaderPackSourceType.ZIP_ARCHIVE, 6, 2_097_152),
            pack("aurora", "Aurora", "/packs/Aurora",
                    ShaderPackSourceType.DIRECTORY, 12, 1_048_576)
    );

    @Test
    void defaultsToAlphabeticalOrderRegardlessOfScanOrder() {
        assertEquals(List.of("Aurora", "Complementary", "Photon", "Stillwater"),
                names(ShaderPackBrowserModel.apply(packs, "", SourceFilter.ALL, SortOrder.NAME)));
    }

    @Test
    void filtersDirectoriesAndArchivesWithoutOpeningPackContents() {
        assertEquals(List.of("Aurora", "Photon"),
                names(ShaderPackBrowserModel.apply(packs, "", SourceFilter.FOLDERS, SortOrder.NAME)));
        assertEquals(List.of("Complementary", "Stillwater"),
                names(ShaderPackBrowserModel.apply(packs, "", SourceFilter.ZIP_ARCHIVES, SortOrder.NAME)));
    }

    @Test
    void sortsByShaderFileCountDescendingAndUsesNameForTies() {
        assertEquals(List.of("Complementary", "Aurora", "Photon", "Stillwater"),
                names(ShaderPackBrowserModel.apply(packs, "", SourceFilter.ALL, SortOrder.MOST_SHADERS)));
    }

    @Test
    void sortsByDeclaredUncompressedSizeDescendingAndUsesNameForTies() {
        assertEquals(List.of("Stillwater", "Aurora", "Complementary", "Photon"),
                names(ShaderPackBrowserModel.apply(packs, "", SourceFilter.ALL, SortOrder.LARGEST)));
    }

    @Test
    void combinesTokenSearchWithSourceFilter() {
        assertEquals(List.of("Complementary"),
                names(ShaderPackBrowserModel.apply(packs, "zip complement",
                        SourceFilter.ZIP_ARCHIVES, SortOrder.NAME)));
        assertEquals(List.of(),
                ShaderPackBrowserModel.apply(packs, "photon",
                        SourceFilter.ZIP_ARCHIVES, SortOrder.NAME));
    }

    @Test
    void leavesInputOrderUntouchedAndReturnsAnImmutableResult() {
        List<ShaderPackDescriptor> result =
                ShaderPackBrowserModel.apply(packs, "", SourceFilter.ALL, SortOrder.NAME);

        assertEquals(List.of("Complementary", "Photon", "Stillwater", "Aurora"),
                packs.stream().map(ShaderPackDescriptor::displayName).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.add(packs.getFirst()));
    }

    @Test
    void filterAndSortControlsCycleInBothFiniteOrders() {
        assertEquals(SourceFilter.FOLDERS, SourceFilter.ALL.next());
        assertEquals(SourceFilter.ZIP_ARCHIVES, SourceFilter.FOLDERS.next());
        assertEquals(SourceFilter.ALL, SourceFilter.ZIP_ARCHIVES.next());
        assertEquals(SortOrder.MOST_SHADERS, SortOrder.NAME.next());
        assertEquals(SortOrder.LARGEST, SortOrder.MOST_SHADERS.next());
        assertEquals(SortOrder.NAME, SortOrder.LARGEST.next());
    }

    @Test
    void rejectsMissingCollectionsAndControlValues() {
        assertThrows(NullPointerException.class,
                () -> ShaderPackBrowserModel.apply(null, "", SourceFilter.ALL, SortOrder.NAME));
        assertThrows(NullPointerException.class,
                () -> ShaderPackBrowserModel.apply(packs, "", null, SortOrder.NAME));
        assertThrows(NullPointerException.class,
                () -> ShaderPackBrowserModel.apply(packs, "", SourceFilter.ALL, null));
    }

    private static List<String> names(List<ShaderPackDescriptor> packs) {
        return packs.stream().map(ShaderPackDescriptor::displayName).toList();
    }
}
