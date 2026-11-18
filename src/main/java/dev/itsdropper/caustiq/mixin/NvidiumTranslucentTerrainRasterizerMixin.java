package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTextureBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.nvidium.renderers.TranslucentTerrainRasterizer")
@Pseudo
public abstract class NvidiumTranslucentTerrainRasterizerMixin {
    @Inject(method = "<init>", at = @At("HEAD"), remap = false, require = 1)
    private static void caustiq$beginTranslucentShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.beginTranslucentPipelineCompile();
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$endTranslucentShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.endPipelineCompile();
    }

    @Inject(method = "raster", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$attachTranslucentGBuffer(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        if (NvidiumShadowRenderer.isShadowRasterization()) return;
        try {
            NvidiumIrisTextureBridge.captureOpaqueDepthSnapshot();
        } catch (RuntimeException exception) {
            dev.itsdropper.caustiq.CaustiqClient.LOGGER.warn(
                    "Could not capture opaque depth for Iris depthtex1; packs requiring it will report an unavailable sampler.",
                    exception);
        }
        NvidiumGBufferTargets.configureOutputRouting(
                NvidiumShaderProgramBridge.outputRoutingFor(NvidiumShaderProgramBridge.PipelineKind.TRANSLUCENT));
        NvidiumGBufferTargets.begin(false);
    }

    @Inject(method = "raster", at = @At(
            value = "INVOKE",
            target = "Lme/cortex/nvidium/gl/shader/Shader;bind()V",
            shift = At.Shift.AFTER), remap = false, require = 1)
    private void caustiq$uploadTranslucentUniforms(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        NvidiumIrisUniformBridge.upload(this);
    }

    @Inject(method = "raster", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$restoreTranslucentGBuffer(
            @Coerce Object pass, int regionCount, long commandAddress,
            @Coerce Object terrainSampler, CallbackInfo callbackInfo) {
        if (!NvidiumShadowRenderer.isShadowRasterization()) {
            NvidiumGBufferTargets.end();
        }
        NvidiumIrisTextureBridge.restoreBindings();
    }
}
