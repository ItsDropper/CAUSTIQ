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
        return NvidiumIrisChunkVertex.isRequested() ? NvidiumIrisChunkVertex.STRIDE : original;
    }

    // STRIDE is a compile-time constant in both upstream vertex formats. The
    // multiplication is also folded by javac, but copyQuad stores the result
    // in a long, so its bytecode constants are 80L and 64L (not int constants).
    @ModifyConstant(method = "copyQuad", constant = @Constant(longValue = 80L),
            remap = false, require = 1)
    private static long caustiq$copyExtendedSodiumQuadStride(long original) {
        return NvidiumIrisChunkVertex.isEnabled()
                ? (long) NvidiumIrisChunkVertex.STRIDE * 4L
                : original;
    }

    @ModifyConstant(method = "copyQuad", constant = @Constant(longValue = 64L),
            remap = false, require = 1)
    private static long caustiq$copyExtendedNvidiumQuadStride(long original) {
        return NvidiumIrisChunkVertex.isEnabled()
                ? (long) NvidiumIrisChunkVertex.STRIDE * 4L
                : original;
    }
}
