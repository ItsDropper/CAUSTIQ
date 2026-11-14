package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

/** Intercepts Nvidium's complete shader stage set immediately before GL linking. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.gl.shader.Shader$Builder", remap = false)
public abstract class NvidiumShaderBuilderMixin {
    @Shadow @Final private Map<?, String> sources;

    @Inject(method = "compile", at = @At("HEAD"), remap = false)
    private void caustiq$adaptTerrainShaderProgram(CallbackInfoReturnable<?> callbackInfo) {
        NvidiumShaderProgramBridge.transformBeforeCompile(sources);
    }
}
