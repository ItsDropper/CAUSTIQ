package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.nvidium.renderers.PrimaryTerrainRasterizer")
public abstract class NvidiumPrimaryTerrainRasterizerMixin {
    @Inject(method = "<init>", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$beginPrimaryShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.beginPipelineCompile(NvidiumShaderProgramBridge.PipelineKind.PRIMARY);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$endPrimaryShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.endPipelineCompile();
    }
}
