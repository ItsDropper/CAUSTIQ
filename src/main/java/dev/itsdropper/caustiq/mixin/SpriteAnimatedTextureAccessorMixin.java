package dev.itsdropper.caustiq.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public interface SpriteAnimatedTextureAccessorMixin {
    @Accessor("frames")
    List<?> caustiq$getFrames();

    @Accessor("frameRowSize")
    int caustiq$getFrameRowSize();
}
