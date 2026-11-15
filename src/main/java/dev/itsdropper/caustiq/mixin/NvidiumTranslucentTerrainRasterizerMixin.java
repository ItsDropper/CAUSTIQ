package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.nvidium.renderers.TranslucentTerrainRasterizer")
public abstract class NvidiumTranslucentTerrainRasterizerMixin {
    @Inject(method = "<init>", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$beginTranslucentShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.beginPipelineCompile(NvidiumShaderProgramBridge.PipelineKind.TRANSLUCENT);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 1)
    private void caustiq$endTranslucentShaderCompilation(CallbackInfo callbackInfo) {
        NvidiumShaderProgramBridge.endPipelineCompile();
    }
}
