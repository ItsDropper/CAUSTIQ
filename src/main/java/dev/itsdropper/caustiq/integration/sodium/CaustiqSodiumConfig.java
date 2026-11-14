package dev.itsdropper.caustiq.integration.sodium;

import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.gui.CaustiqSettingsScreen;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Registers native Sodium controls through its public, optional configuration API. */
public final class CaustiqSodiumConfig implements ConfigEntryPoint {
    private static final Identifier ENABLED = Identifier.fromNamespaceAndPath("caustiq", "enabled");
    private static final Identifier COMPILE_BUDGET = Identifier.fromNamespaceAndPath("caustiq", "compile_budget");
    private static final Identifier PACK_SETTINGS = Identifier.fromNamespaceAndPath("caustiq", "pack_settings");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        CaustiqConfig config = CaustiqConfig.get();
        builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xD9364F))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("caustiq.settings.category"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(ENABLED)
                                        .setName(Component.translatable("caustiq.settings.enabled_option"))
                                        .setTooltip(Component.translatable("caustiq.settings.enabled_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(config::setEnabled, config::enabled)
                                        .setDefaultValue(false))
                                .addOption(builder.createIntegerOption(COMPILE_BUDGET)
                                        .setName(Component.translatable("caustiq.settings.compile_budget"))
                                        .setTooltip(Component.translatable("caustiq.settings.compile_budget_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(config::setCompileBudget, config::compileBudget)
                                        .setRange(CaustiqConfig.MIN_COMPILE_BUDGET, CaustiqConfig.MAX_COMPILE_BUDGET, 1)
                                        .setValueFormatter(value -> Component.translatable(
                                                "caustiq.settings.compile_budget_value", value))
                                        .setDefaultValue(1))
                                .addOption(builder.createExternalButtonOption(PACK_SETTINGS)
                                        .setName(Component.translatable("caustiq.settings.pack_settings"))
                                        .setTooltip(Component.translatable("caustiq.settings.pack_settings_tooltip"))
                                        .setScreenConsumer(screen -> Minecraft.getInstance().setScreen(
                                                new CaustiqSettingsScreen(screen))))));
    }
}
