package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
public abstract class SodiumBlockRendererContextMixin {
    @Inject(method = "renderModel", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$pushBlockMetadata(
            @Coerce Object model, BlockState state, BlockPos position, BlockPos origin, CallbackInfo callbackInfo) {
        NvidiumIrisVertexContext.push(state, position, false);
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$popBlockMetadata(
            @Coerce Object model, BlockState state, BlockPos position, BlockPos origin, CallbackInfo callbackInfo) {
        NvidiumIrisVertexContext.pop();
    }
}
