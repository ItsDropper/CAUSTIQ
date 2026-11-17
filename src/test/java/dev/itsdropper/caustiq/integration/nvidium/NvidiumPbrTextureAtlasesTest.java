package dev.itsdropper.caustiq.integration.nvidium;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiumPbrTextureAtlasesTest {
    @Test
    void resolvesResourcePackNormalAndSpecularAtlasSpritePaths() {
        Identifier sprite = Identifier.fromNamespaceAndPath("minecraft", "block/stone");
        assertEquals("minecraft:textures/block/stone_n.png",
                NvidiumPbrTextureAtlases.pbrResourceId(sprite, true).toString());
        assertEquals("minecraft:textures/block/stone_s.png",
                NvidiumPbrTextureAtlases.pbrResourceId(sprite, false).toString());
    }

    @Test
    void preservesOptifineCitSpriteResourcePaths() {
        Identifier sprite = Identifier.fromNamespaceAndPath("minecraft", "optifine/cit/stone");
        assertEquals("minecraft:optifine/cit/stone_n.png",
                NvidiumPbrTextureAtlases.pbrResourceId(sprite, true).toString());
    }
}
