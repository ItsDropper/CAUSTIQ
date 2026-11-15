package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IrisBlockPropertiesParserTest {
    private final IrisBlockPropertiesParser parser = new IrisBlockPropertiesParser();

    @Test
    void parsesBlockIdsListsAndExactStateSelectors() {
        var result = parser.parse("""
                # Shader pack material IDs
                block.1=minecraft:stone minecraft:granite
                block.7=minecraft:oak_log:axis=y
                block.9=minecraft:oak_log[axis=x]
                block.11=oak_sign
                block.12=oak_log:axis=z
                block.13=oak_sign[rotation=4]
                """);

        assertEquals(1, result.get("minecraft:stone"));
        assertEquals(1, result.get("minecraft:granite"));
        assertEquals(7, result.get("minecraft:oak_log[axis=y]"));
        assertEquals(9, result.get("minecraft:oak_log[axis=x]"));
        assertFalse(result.containsKey("minecraft:oak_log"));
        assertEquals(11, result.get("minecraft:oak_sign"));
        assertEquals(12, result.get("minecraft:oak_log[axis=z]"));
        assertEquals(13, result.get("minecraft:oak_sign[rotation=4]"));
    }

    @Test
    void selectsOnlyTheActiveMinecraftVersionBranch() {
        String source = """
                #if MC_VERSION >= 11300
                block.10035=mossy_stone_brick_stairs stone_brick_stairs
                #else
                block.10033=stone_brick_stairs
                #endif
                """;

        assertEquals(java.util.Map.of(
                "minecraft:mossy_stone_brick_stairs", 10035,
                "minecraft:stone_brick_stairs", 10035),
                parser.parse(source, 12111));
        assertEquals(java.util.Map.of("minecraft:stone_brick_stairs", 10033),
                parser.parse(source, 11202));
    }

    @Test
    void rejectsMalformedAndConflictingMaterialMappings() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("block.1=minecraft:oak_log:axis"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("block.1=minecraft:stone\nblock.2=minecraft:stone"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("block.2147483647=minecraft:stone"));
    }

    @Test
    void ignoresNonBlockPropertiesAndComments() {
        var result = parser.parse("""
                entity.1=minecraft:zombie
                item.2=minecraft:diamond_sword
                block.3=minecraft:dirt # inline comment
                """);
        assertEquals(java.util.Map.of("minecraft:dirt", 3), result);
    }
}
