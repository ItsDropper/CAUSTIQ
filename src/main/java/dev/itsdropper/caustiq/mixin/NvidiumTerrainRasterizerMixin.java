package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTextureBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShadowRenderer;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumTerrainPassBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.nvidium.renderers.PrimaryTerrainRasterizer")
public abstract class NvidiumTerrainRasterizerMixin {
    @Inject(method = "raster", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$attachTerrainGBuffer(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        if (NvidiumShadowRenderer.isShadowRasterization()) return;
        NvidiumGBufferTargets.configureOutputRouting(
                NvidiumShaderProgramBridge.outputRoutingFor(NvidiumShaderProgramBridge.PipelineKind.PRIMARY));
        NvidiumGBufferTargets.begin(NvidiumTerrainPassBridge.shouldClearAttachments(pass));
    }

    @Inject(method = "raster", at = @At(
            value = "INVOKE",
            target = "Lme/cortex/nvidium/gl/shader/Shader;bind()V",
            shift = At.Shift.AFTER), remap = false, require = 1)
    private void caustiq$uploadIrisTerrainUniforms(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        NvidiumIrisUniformBridge.upload(this);
    }

    @Inject(method = "raster", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$restoreTerrainGBuffer(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        if (NvidiumShadowRenderer.isShadowRasterization()) {
            NvidiumIrisTextureBridge.restoreBindings();
            return;
        }
        NvidiumGBufferTargets.end();
        NvidiumIrisTextureBridge.restoreBindings();
    }
}
