package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderPackFilterTest {
    private static ShaderPackDescriptor pack(String id, String name, String source, ShaderPackSourceType type) {
        return new ShaderPackDescriptor(id, name, Path.of(source), "",
                type, ShaderPackLayout.OPTIFINE_STYLE, 3, 2, 1024);
    }

    private final List<ShaderPackDescriptor> packs = List.of(
            pack("complementary-reimagined", "Complementary Reimagined", "/packs/Complementary.zip",
                    ShaderPackSourceType.ZIP_ARCHIVE),
            pack("photon", "Photon", "/packs/Photon", ShaderPackSourceType.DIRECTORY),
            pack("stillwater", "Stillwater", "/packs/Stillwater.zip", ShaderPackSourceType.ZIP_ARCHIVE)
    );

    @Test
    void blankQueryPreservesTheOriginalOrder() {
        assertEquals(packs, ShaderPackFilter.filter(packs, "  "));
        assertEquals(packs, ShaderPackFilter.filter(packs, null));
    }

    @Test
    void matchesNamesWithoutCaseSensitivity() {
        assertEquals(List.of(packs.get(0)), ShaderPackFilter.filter(packs, "cOmPlEmEnTaRy"));
    }

    @Test
    void allTokensMustMatchAcrossSearchableMetadata() {
        assertEquals(List.of(packs.get(0)), ShaderPackFilter.filter(packs, "reimagined zip"));
        assertEquals(List.of(packs.get(1)), ShaderPackFilter.filter(packs, "photon folder"));
    }

    @Test
    void sourceFilenameAndStableIdAreSearchable() {
        assertEquals(List.of(packs.get(0)), ShaderPackFilter.filter(packs, "Complementary.zip"));
        assertEquals(List.of(packs.get(2)), ShaderPackFilter.filter(packs, "stillwater"));
    }

    @Test
    void noMatchReturnsAnEmptyImmutableList() {
        List<ShaderPackDescriptor> result = ShaderPackFilter.filter(packs, "does-not-exist");
        assertEquals(List.of(), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(packs.get(0)));
    }

    @Test
    void rejectsNullPackLists() {
        assertThrows(NullPointerException.class, () -> ShaderPackFilter.filter(null, ""));
    }
}
