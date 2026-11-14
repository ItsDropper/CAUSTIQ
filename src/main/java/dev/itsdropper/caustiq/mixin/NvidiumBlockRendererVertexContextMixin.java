package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures block identity and local position while Sodium emits its terrain quad vertices. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer",
        remap = false)
public abstract class NvidiumBlockRendererVertexContextMixin {
    @Unique private NvidiumIrisVertexContext.Scope caustiq$scope;

    @Inject(method = "renderModel", at = @At("HEAD"), remap = false, require = 1)
    private void caustiq$beginBlock(BlockState state, BlockPos position, BlockPos origin,
                                    CallbackInfo callbackInfo) {
        caustiq$scope = NvidiumIrisVertexContext.enter(state, position, false);
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false, require = 1)
    private void caustiq$endBlock(BlockState state, BlockPos position, BlockPos origin,
                                  CallbackInfo callbackInfo) {
        if (caustiq$scope != null) {
            caustiq$scope.close();
            caustiq$scope = null;
        }
    }
}
