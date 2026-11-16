package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTextureBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Nvidium's real renderer entrypoints and expands its arena allocation
 * stride to match the extended Sodium/Iris terrain vertex ABI.
 */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.NvidiumWorldRenderer", remap = false)
public abstract class NvidiumWorldRendererMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$attachWorldRenderer(CallbackInfo callbackInfo) {
        NvidiumRenderBridge.attachWorldRenderer(this);
    }

    @Inject(method = "delete", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$releaseShaderTextures(CallbackInfo callbackInfo) {
        NvidiumIrisTextureBridge.destroy();
        NvidiumIrisUniformBridge.destroy();
        NvidiumShadowRenderer.destroy(this);
    }

    @Inject(method = "reloadShaders", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$reloadShadowRasterizer(CallbackInfo callbackInfo) {
        NvidiumShadowRenderer.reload(this);
    }

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/nvidium/managers/SectionManager;<init>(Lme/cortex/nvidium/gl/RenderDevice;JLme/cortex/nvidium/util/UploadingBufferStream;ILme/cortex/nvidium/NvidiumWorldRenderer;)V",
                    remap = false),
            index = 3,
            remap = false,
            require = 1)
    private int caustiq$allocateExtendedTerrainStride(int originalStride) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : originalStride;
    }

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
        NvidiumRenderBridge.attachWorldRenderer(this);
        NvidiumIrisUniformBridge.capture(matrices, x, y, z);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$renderShadowTerrain(
            @Coerce Object pass, @Coerce Object viewport, @Coerce Object fogParameters,
            @Coerce Object matrices, double x, double y, double z,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        NvidiumIrisUniformBridge.captureCenterDepth();
        NvidiumShadowRenderer.render(this, pass, fogParameters, x, y, z, terrainSampler);
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
