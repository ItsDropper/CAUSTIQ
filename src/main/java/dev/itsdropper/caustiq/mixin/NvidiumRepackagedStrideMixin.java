package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

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

    @Redirect(
            method = "copyQuad",
            at = @At(value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex;STRIDE:I",
                    opcode = Opcodes.GETSTATIC),
            remap = false,
            require = 1)
    private static int caustiq$copyExtendedVertexStride() {
        return NvidiumIrisChunkVertex.isEnabled()
                ? NvidiumIrisChunkVertex.STRIDE
                : CompactChunkVertex.STRIDE;
    }
}
