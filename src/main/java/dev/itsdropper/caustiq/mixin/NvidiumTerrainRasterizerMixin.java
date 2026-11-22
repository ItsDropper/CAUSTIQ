package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTextureBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShadowRenderer;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumTerrainPassBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.nvidium.renderers.PrimaryTerrainRasterizer")
public abstract class NvidiumTerrainRasterizerMixin {
    @Unique private boolean caustiq$gbufferAttached;
    @Inject(method = "raster", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$attachTerrainGBuffer(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        if (NvidiumShadowRenderer.isShadowRasterization()) return;
        NvidiumRenderBridge.primaryRasterized(regionCount);
        NvidiumGBufferTargets.configureOutputRouting(
                NvidiumShaderProgramBridge.outputRoutingFor(NvidiumShaderProgramBridge.PipelineKind.PRIMARY));
        caustiq$gbufferAttached = NvidiumGBufferTargets.begin(
                NvidiumTerrainPassBridge.shouldClearAttachments(pass));
        if (caustiq$gbufferAttached) NvidiumRenderBridge.gbufferAttached();
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
        if (caustiq$gbufferAttached) {
            NvidiumGBufferTargets.completeTerrainDraw();
            NvidiumRenderBridge.gbufferDrawCompleted();
            if (Boolean.getBoolean("caustiq.strictGpuCi")) {
                NvidiumRenderBridge.Snapshot evidence = NvidiumRenderBridge.snapshot();
                if (evidence.gbufferNonClearReadbacks() == 0
                        && evidence.completedGbufferDraws() % 30 == 0
                        && NvidiumGBufferTargets.hasNonClearPixelsForStrictCi()) {
                    NvidiumRenderBridge.gbufferPixelsObserved();
                    dev.itsdropper.caustiq.CaustiqClient.LOGGER.info(
                            "CAUSTIQ_STRICT_EVIDENCE: GPU readback found non-clear terrain fragment output in the Nvidium G-buffer.");
                }
            }
        }
        NvidiumGBufferTargets.end();
        caustiq$gbufferAttached = false;
        NvidiumIrisTextureBridge.restoreBindings();
    }
}
