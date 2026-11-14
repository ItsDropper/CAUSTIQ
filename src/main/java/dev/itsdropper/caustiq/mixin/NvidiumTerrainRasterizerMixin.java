package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes Nvidium's primary terrain draw through CAUSTIQ's auxiliary G-buffer attachments. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.renderers.PrimaryTerrainRasterizer", remap = false)
public abstract class NvidiumTerrainRasterizerMixin {
    @Inject(method = "raster", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$attachTerrainGBuffer(CallbackInfo callbackInfo) {
        NvidiumGBufferTargets.begin();
    }

    @Inject(method = "raster",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/nvidium/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER),
            remap = false, require = 1)
    private void caustiq$uploadIrisUniforms(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        NvidiumIrisUniformBridge.upload(this);
    }

    @Inject(method = "raster", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$restoreTerrainFramebuffer(CallbackInfo callbackInfo) {
        NvidiumGBufferTargets.end();
    }
}
