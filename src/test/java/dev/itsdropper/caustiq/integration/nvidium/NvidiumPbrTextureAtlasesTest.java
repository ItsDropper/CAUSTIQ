package dev.itsdropper.caustiq.integration.nvidium;

import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void mapsAnimatedPbrSpriteFramesUsingMinecraftRowMajorFrameIndices() {
        BufferedImage sheet = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        assertArrayEquals(new int[]{16, 16, 16, 16},
                NvidiumPbrTextureAtlases.frameRegionForTests(sheet, 16, 16, 5, 4, 8));
        assertArrayEquals(new int[]{48, 16, 16, 16},
                NvidiumPbrTextureAtlases.frameRegionForTests(sheet, 16, 16, 99, 4, 8));
    }

    @Test
    void scalesSingleFramePbrMapsWithoutTreatingThemAsAnimationSheets() {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        assertArrayEquals(new int[]{0, 0, 32, 16},
                NvidiumPbrTextureAtlases.frameRegionForTests(image, 16, 16, 3, 1, 1));
    }

    @Test
    void preservesOptifineCitSpriteResourcePaths() {
        Identifier sprite = Identifier.fromNamespaceAndPath("minecraft", "optifine/cit/stone");
        assertEquals("minecraft:optifine/cit/stone_n.png",
                NvidiumPbrTextureAtlases.pbrResourceId(sprite, true).toString());
    }
}
