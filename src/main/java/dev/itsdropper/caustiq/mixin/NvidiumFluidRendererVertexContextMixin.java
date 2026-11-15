package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures fluid material identity in Sodium's concrete Fabric renderer implementation. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl",
        remap = false)
public abstract class NvidiumFluidRendererVertexContextMixin {
    @Unique private NvidiumIrisVertexContext.Scope caustiq$scope;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$beginFluid(
            @Coerce Object levelSlice, BlockState blockState, FluidState fluidState,
            BlockPos position, BlockPos origin, @Coerce Object geometryCollector,
            @Coerce Object buffers, CallbackInfo callbackInfo) {
        caustiq$scope = NvidiumIrisVertexContext.enter(fluidState.createLegacyBlock(), position, true);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$endFluid(
            @Coerce Object levelSlice, BlockState blockState, FluidState fluidState,
            BlockPos position, BlockPos origin, @Coerce Object geometryCollector,
            @Coerce Object buffers, CallbackInfo callbackInfo) {
        if (caustiq$scope != null) {
            caustiq$scope.close();
            caustiq$scope = null;
        }
    }
}
