package dev.itsdropper.caustiq.integration.sodium;

import dev.itsdropper.caustiq.gui.CaustiqSettingsScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Adds Caustiq to Sodium through its public config API; Sodium remains optional. */
public final class CaustiqSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xD9364F))
                // No icon until Caustiq has a monochrome Sodium-compatible asset.
                .addPage(builder.createExternalPage()
                        .setName(Component.translatable("caustiq.settings.category"))
                        .setScreenConsumer(screen ->
                                Minecraft.getInstance().setScreen(new CaustiqSettingsScreen(screen))));
    }
}
