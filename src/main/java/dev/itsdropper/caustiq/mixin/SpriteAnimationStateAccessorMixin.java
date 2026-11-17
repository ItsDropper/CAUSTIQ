package dev.itsdropper.caustiq.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public interface SpriteAnimationStateAccessorMixin {
    @Accessor("frame")
    int caustiq$getFrame();

    @Accessor("animationInfo")
    SpriteAnimatedTextureAccessorMixin caustiq$getAnimationInfo();
}
