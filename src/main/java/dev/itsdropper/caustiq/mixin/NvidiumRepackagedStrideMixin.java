package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps Nvidium's CPU-side quad repackaging and copy size in lockstep with the
 * selected extended Sodium vertex format.
 */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility", remap = false)
public abstract class NvidiumRepackagedStrideMixin {
    @ModifyVariable(method = "repackage", at = @At("STORE"), ordinal = 0,
            remap = false, require = 1)
    private static int caustiq$useExtendedVertexStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : original;
    }

    // STRIDE is a compile-time constant in both upstream vertex formats, so javac
    // inlines the multiplied quad sizes (80 and 64) into copyQuad. A field
    // redirect cannot match bytecode that contains no GETSTATIC instruction.
    @ModifyConstant(method = "copyQuad", constant = @Constant(intValue = 80),
            remap = false, require = 1)
    private static int caustiq$copyExtendedSodiumQuadStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled()
                ? NvidiumIrisChunkVertex.STRIDE * 4
                : original;
    }

    @ModifyConstant(method = "copyQuad", constant = @Constant(intValue = 64),
            remap = false, require = 1)
    private static int caustiq$copyExtendedNvidiumQuadStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled()
                ? NvidiumIrisChunkVertex.STRIDE * 4
                : original;
    }
}
