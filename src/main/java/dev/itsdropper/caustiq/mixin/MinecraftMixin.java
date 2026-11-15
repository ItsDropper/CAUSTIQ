package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.shaderpack.CaustiqCiShaderValidation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the completed Minecraft frame after GameRenderer has blitted its
 * main target to the window back buffer.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void caustiq$runCiShaderValidation(CallbackInfo callbackInfo) {
        CaustiqCiShaderValidation.runFromEnvironment();
    }
}
