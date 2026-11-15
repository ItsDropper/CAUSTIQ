package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import dev.itsdropper.caustiq.shaderpack.CaustiqCiShaderValidation;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void caustiq$runCiShaderValidation(CallbackInfo callbackInfo) {
        CaustiqCiShaderValidation.runFromEnvironment();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void caustiq$selectTerrainDimension(CallbackInfo callbackInfo) {
        ShaderRuntime.activateTerrainAdapterForCurrentWorld();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void caustiq$compileShaderPrograms(CallbackInfo callbackInfo) {
        ShaderRuntime.onWorldRender();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void caustiq$releaseShaderPrograms(CallbackInfo callbackInfo) {
        ShaderRuntime.shutdown();
    }
}
