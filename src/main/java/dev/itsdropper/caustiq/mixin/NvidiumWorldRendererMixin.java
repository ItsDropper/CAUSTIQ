package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Nvidium's real renderer entrypoints. Nvidium owns terrain drawing;
 * CAUSTIQ only observes completion so its final pass can run afterward.
 */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.NvidiumWorldRenderer")
public abstract class NvidiumWorldRendererMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$captureIrisTerrainUniforms(
            @Coerce Object pass,
            @Coerce Object viewport,
            @Coerce Object fogParameters,
            @Coerce Object matrices,
            double x,
            double y,
            double z,
            @Coerce Object terrainSampler,
            CallbackInfo callbackInfo) {
        NvidiumIrisUniformBridge.capture(matrices, x, y, z);
    }

    @Inject(method = "renderFrame", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$solidPassStarted(CallbackInfo callbackInfo) {
        NvidiumRenderBridge.solidPassStarted();
    }

    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$solidPassCompleted(CallbackInfo callbackInfo) {
        NvidiumRenderBridge.solidPassCompleted();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$translucentPassStarted(CallbackInfo callbackInfo) {
        NvidiumRenderBridge.translucentPassStarted();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$translucentPassCompleted(CallbackInfo callbackInfo) {
        NvidiumRenderBridge.translucentPassCompleted();
    }
}
