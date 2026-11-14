package dev.itsdropper.caustiq.mixin;

import dev.itsdropper.caustiq.gui.CaustiqSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void caustiq$addShaderSettingsButton(CallbackInfo callbackInfo) {
        Screen screen = (Screen) (Object) this;
        Button button = Button.builder(Component.translatable("caustiq.settings.button"),
                        ignored -> Minecraft.getInstance().setScreen(new CaustiqSettingsScreen(screen)))
                .bounds(screen.width / 2 - 100, screen.height - 52, 200, 20)
                .build();
        ((ScreenAccessor) (Object) screen).caustiq$addRenderableWidget(button);
    }
}
