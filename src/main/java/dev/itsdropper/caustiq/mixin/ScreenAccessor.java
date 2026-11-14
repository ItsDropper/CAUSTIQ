package dev.itsdropper.caustiq.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Screen's protected widget-registration method to the video-options
 * mixin. The method belongs to Screen, not VideoSettingsScreen; shadowing it
 * on the subclass makes Mixin reject the target during class transformation.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T caustiq$addRenderableWidget(T widget);
}
