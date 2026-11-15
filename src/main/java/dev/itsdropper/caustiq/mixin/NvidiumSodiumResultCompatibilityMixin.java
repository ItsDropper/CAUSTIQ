package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Constant;

/** Makes Nvidium's CPU repackaging preserve the complete 40-byte Sodium/Iris vertex. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility", remap = false)
public abstract class NvidiumSodiumResultCompatibilityMixin {
    @ModifyVariable(method = "repackage", at = @At("STORE"), ordinal = 0, remap = false, require = 1)
    private static int caustiq$useExtendedVertexStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : original;
    }

    @ModifyConstant(method = "copyQuad", constant = @Constant(intValue = 20), remap = false, require = 1)
    private static int caustiq$copyExtendedQuadStride(int original) {
        return NvidiumIrisChunkVertex.isEnabled() ? NvidiumIrisChunkVertex.STRIDE : original;
    }
}
