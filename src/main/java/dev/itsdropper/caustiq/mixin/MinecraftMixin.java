package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.shaderpack.CaustiqCiShaderValidation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waits for the title screen where possible, with a bounded startup fallback,
 * then validates Photon and captures the completed Minecraft frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private int caustiq$titleScreenFrames;
    private int caustiq$elapsedFrames;
    private boolean caustiq$validationTriggered;

    @Inject(method = "runTick", at = @At("TAIL"))
    private void caustiq$runCiShaderValidation(CallbackInfo callbackInfo) {
        if (caustiq$validationTriggered
                || !"1".equals(System.getenv("CAUSTIQ_CI_SHADER_VALIDATION"))) return;
        Minecraft client = Minecraft.getInstance();
        caustiq$elapsedFrames++;
        if (client.screen instanceof TitleScreen) {
            caustiq$titleScreenFrames++;
        } else {
            caustiq$titleScreenFrames = 0;
        }

        if (caustiq$titleScreenFrames >= 20) {
            caustiq$validationTriggered = true;
            CaustiqCiShaderValidation.runFromEnvironment();
        } else if (caustiq$elapsedFrames >= 200) {
            caustiq$validationTriggered = true;
            CaustiqClient.LOGGER.warn(
                    "Photon CI capture reached its 10-second startup fallback before the title screen.");
            CaustiqCiShaderValidation.runFromEnvironment();
        }
    }
}
