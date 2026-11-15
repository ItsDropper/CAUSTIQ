package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Supplies the extended packed format to Sodium before Nvidium repackages chunk meshes. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
        remap = false, priority = 900)
public abstract class NvidiumExtendedVertexFormatMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"),
            index = 1,
            remap = false,
            require = 1)
    private ChunkVertexType caustiq$installExtendedIrisFormat(ChunkVertexType original) {
        return NvidiumIrisChunkVertex.isRequested() ? NvidiumIrisChunkVertex.INSTANCE : original;
    }
}
