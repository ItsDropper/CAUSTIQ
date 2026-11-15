package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisChunkVertex;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumCompatibility;
import net.minecraft.client.multiplayer.ClientLevel;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
    /**
     * Nvidium's IS_COMPATIBLE flag is populated only after OpenGL capability
     * detection. Commit the Sodium-format request here, before Nvidium's
     * higher-priority ModifyArg selects its own ChunkVertexType.
     */
    @Inject(method = "<init>", at = @At("HEAD"), remap = false)
    private static void caustiq$activateExtendedFormatAfterCapabilityCheck(
            ClientLevel level, int renderDistance, SortBehavior sortBehavior,
            CommandList commandList, CallbackInfo callbackInfo) {
        if (!NvidiumCompatibility.enableExtendedVertexFormat()) {
            NvidiumIrisChunkVertex.disable();
        }
    }

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"),
            index = 1,
            remap = false,
            require = 1)
    private ChunkVertexType caustiq$installExtendedIrisFormat(ChunkVertexType original) {
        return NvidiumIrisChunkVertex.isCommitted() ? NvidiumIrisChunkVertex.INSTANCE : original;
    }
}
