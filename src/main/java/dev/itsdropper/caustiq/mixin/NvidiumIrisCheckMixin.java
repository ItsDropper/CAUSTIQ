package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve Nvidium's Iris opt-out unless a real CAUSTIQ terrain adapter is installed. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.IrisCheck", remap = false)
public abstract class NvidiumIrisCheckMixin {
    @Inject(method = "checkIrisShouldDisable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void caustiq$allowAdaptedIrisTerrain(CallbackInfoReturnable<Boolean> callbackInfo) {
        // This is the first safe lifecycle point for resource-backed shader
        // preparation and is still before Nvidium applies its Iris opt-out.
        ShaderRuntime.prepareSelectedNvidiumTerrainAdapter();
        if (ShaderRuntime.activateTerrainAdapterForCurrentWorld()) callbackInfo.setReturnValue(true);
    }
}
