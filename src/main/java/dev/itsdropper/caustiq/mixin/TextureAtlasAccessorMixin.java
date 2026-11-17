package dev.itsdropper.caustiq.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Mapped access to the block atlas layout used to construct aligned PBR atlases. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessorMixin {
    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> caustiq$getTexturesByName();

    @Accessor("width")
    int caustiq$getWidth();

    @Accessor("height")
    int caustiq$getHeight();
}
