package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer")
public abstract class SodiumFluidRendererContextMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$pushFluidMetadata(
            @Coerce Object level, BlockState blockState, FluidState fluidState,
            BlockPos position, BlockPos origin, @Coerce Object collector,
            @Coerce Object buffers, CallbackInfo callbackInfo) {
        NvidiumIrisVertexContext.push(fluidState.createLegacyBlock(), position, true);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$popFluidMetadata(
            @Coerce Object level, BlockState blockState, FluidState fluidState,
            BlockPos position, BlockPos origin, @Coerce Object collector,
            @Coerce Object buffers, CallbackInfo callbackInfo) {
        NvidiumIrisVertexContext.pop();
    }
}
