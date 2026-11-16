package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderPackSelectionTest {
    private static ShaderPackDescriptor pack(String id) {
        return new ShaderPackDescriptor(id, id, Path.of(id), "",
                ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 1);
    }

    @Test
    void movesForwardAndBackwardWithWraparound() {
        List<ShaderPackDescriptor> packs = List.of(pack("a"), pack("b"), pack("c"));

        assertEquals(1, ShaderPackSelection.nextIndex(packs, "a", 1));
        assertEquals(2, ShaderPackSelection.nextIndex(packs, "a", -1));
        assertEquals(0, ShaderPackSelection.nextIndex(packs, "c", 1));
        assertEquals(0, ShaderPackSelection.nextIndex(packs, "b", 2));
    }

    @Test
    void recoversMissingSelectionInRequestedDirection() {
        List<ShaderPackDescriptor> packs = List.of(pack("a"), pack("b"));

        assertEquals(0, ShaderPackSelection.nextIndex(packs, "removed-pack", 1));
        assertEquals(1, ShaderPackSelection.nextIndex(packs, "removed-pack", -1));
    }

    @Test
    void returnsNoSelectionForEmptyList() {
        assertEquals(-1, ShaderPackSelection.nextIndex(List.of(), "", 1));
    }

    @Test
    void rejectsZeroDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackSelection.nextIndex(List.of(pack("a")), "a", 0));
    }
}
