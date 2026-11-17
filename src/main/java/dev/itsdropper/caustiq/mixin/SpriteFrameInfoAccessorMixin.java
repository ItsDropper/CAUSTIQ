package dev.itsdropper.caustiq.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$FrameInfo")
public interface SpriteFrameInfoAccessorMixin {
    @Accessor("index")
    int caustiq$getIndex();
}
