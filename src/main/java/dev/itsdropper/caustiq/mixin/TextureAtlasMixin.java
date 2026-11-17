package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumPbrTextureAtlases;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps CAUSTIQ's resource-pack PBR atlas lifecycle aligned with Minecraft's block atlas. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("HEAD"))
    private void caustiq$invalidatePbrAtlas(SpriteLoader.Preparations preparations, CallbackInfo callbackInfo) {
        NvidiumPbrTextureAtlases.onAtlasReload((TextureAtlas) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void caustiq$syncAnimatedPbrMaps(CallbackInfo callbackInfo) {
        NvidiumPbrTextureAtlases.onAtlasTick((TextureAtlas) (Object) this);
    }
}
