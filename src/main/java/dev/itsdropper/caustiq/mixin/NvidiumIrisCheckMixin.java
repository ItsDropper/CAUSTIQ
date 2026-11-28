package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.CaustiqClient;
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
        // Keep this at the existing pre-world decision point: discovery may still
        // be running because startup no longer blocks the title-screen bootstrap.
        CaustiqClient.awaitStartupScan();
        ShaderRuntime.prepareSelectedNvidiumTerrainAdapter();
        if (ShaderRuntime.activateTerrainAdapterForCurrentWorld()) callbackInfo.setReturnValue(true);
    }
}
