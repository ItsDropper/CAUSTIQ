package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumPbrTextureAtlases;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static boolean caustiq$pbrAtlasValidationRun;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void caustiq$selectTerrainDimension(CallbackInfo callbackInfo) {
        if (!caustiq$pbrAtlasValidationRun
                && "1".equals(System.getenv("CAUSTIQ_CI_VALIDATE_PBR_ATLAS"))) {
            caustiq$pbrAtlasValidationRun = true;
            try {
                NvidiumPbrTextureAtlases.validateForTests();
                CaustiqClient.LOGGER.info("CAUSTIQ_CI_PBR_ATLAS_PASS");
            } catch (RuntimeException exception) {
                CaustiqClient.LOGGER.error("CAUSTIQ_CI_PBR_ATLAS_FAIL", exception);
            }
        }
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
