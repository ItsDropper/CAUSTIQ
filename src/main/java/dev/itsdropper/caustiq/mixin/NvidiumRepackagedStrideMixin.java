package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keeps Nvidium's CPU-side quad repackaging stride in lockstep with the selected Sodium format. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility", remap = false)
public abstract class NvidiumRepackagedStrideMixin {
    @ModifyVariable(method = "repackage", at = @At("STORE"), ordinal = 0,
            remap = false, require = 1)
    private static int caustiq$useExtendedVertexStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : original;
    }

    @ModifyConstant(method = "copyQuad", constant = @Constant(intValue = 20),
            remap = false, require = 1)
    private static int caustiq$copyFullExtendedQuad(int original) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : original;
    }
}
