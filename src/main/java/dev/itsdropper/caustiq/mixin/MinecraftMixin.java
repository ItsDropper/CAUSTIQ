package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.shaderpack.CaustiqCiShaderValidation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waits until Minecraft has reached and rendered its title screen before
 * validating Photon and capturing the completed client frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private int caustiq$titleScreenFrames;

    @Inject(method = "runTick", at = @At("TAIL"))
    private void caustiq$runCiShaderValidation(CallbackInfo callbackInfo) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof TitleScreen) {
            if (++caustiq$titleScreenFrames >= 20) {
                CaustiqCiShaderValidation.runFromEnvironment();
            }
        } else {
            caustiq$titleScreenFrames = 0;
        }
    }
}
