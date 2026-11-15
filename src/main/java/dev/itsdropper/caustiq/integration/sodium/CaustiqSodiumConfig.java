package dev.itsdropper.caustiq.integration.sodium;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Registers CAUSTIQ controls directly in Sodium's native video-settings pages. */
public final class CaustiqSodiumConfig implements ConfigEntryPoint {
    private static final Identifier ENABLED = Identifier.fromNamespaceAndPath("caustiq", "enabled");
    private static final Identifier PACK = Identifier.fromNamespaceAndPath("caustiq", "pack");
    private static final Identifier COMPILE_BUDGET = Identifier.fromNamespaceAndPath("caustiq", "compile_budget");
    private static final Identifier REFRESH = Identifier.fromNamespaceAndPath("caustiq", "refresh");

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
                                        .setApplyHook(ignored -> ShaderRuntime.requestReload())
                                        .setDefaultValue(false))
                                .addOption(builder.createIntegerOption(PACK)
                                        .setName(Component.translatable("caustiq.settings.selected_pack"))
                                        .setTooltip(Component.translatable("caustiq.settings.selected_pack_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(CaustiqSodiumConfig::selectPackAtIndex,
                                                CaustiqSodiumConfig::selectedPackIndex)
                                        .setRangeProvider(ignored -> new Range(0, availablePacks().size(), 1),
                                                ConfigState.UPDATE_ON_REBUILD)
                                        .setValueFormatter(CaustiqSodiumConfig::formatPackIndex)
                                        .setApplyHook(ignored -> ShaderRuntime.requestReload())
                                        .setDefaultValue(0))
                                .addOption(builder.createIntegerOption(COMPILE_BUDGET)
                                        .setName(Component.translatable("caustiq.settings.compile_budget"))
                                        .setTooltip(Component.translatable("caustiq.settings.compile_budget_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(config::setCompileBudget, config::compileBudget)
                                        .setRange(CaustiqConfig.MIN_COMPILE_BUDGET,
                                                CaustiqConfig.MAX_COMPILE_BUDGET, 1)
                                        .setValueFormatter(value -> Component.translatable(
                                                "caustiq.settings.compile_budget_value", value))
                                        .setDefaultValue(1))
                                .addOption(builder.createExternalButtonOption(REFRESH)
                                        .setName(Component.translatable("caustiq.settings.refresh"))
                                        .setTooltip(Component.translatable("caustiq.settings.refresh_tooltip"))
                                        .setScreenConsumer(ignored -> CaustiqClient.refreshShaderPacks()))));
    }

    private static List<ShaderPackDescriptor> availablePacks() {
        ShaderPackScanResult result = CaustiqClient.scanResult();
        return result == null ? List.of() : result.packs();
    }

    private static int selectedPackIndex() {
        String selectedId = CaustiqConfig.get().selectedPackId();
        List<ShaderPackDescriptor> packs = availablePacks();
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).id().equals(selectedId)) return i + 1;
        }
        return 0;
    }

    private static void selectPackAtIndex(int index) {
        List<ShaderPackDescriptor> packs = availablePacks();
        if (index <= 0 || packs.isEmpty()) {
            CaustiqConfig.get().selectPack("");
            return;
        }
        int safeIndex = Math.clamp(index - 1, 0, packs.size() - 1);
        CaustiqConfig.get().selectPack(packs.get(safeIndex).id());
    }

    private static Component formatPackIndex(int index) {
        List<ShaderPackDescriptor> packs = availablePacks();
        if (index <= 0) return Component.translatable("caustiq.settings.select_pack");
        if (packs.isEmpty()) return Component.translatable("caustiq.settings.no_packs");
        int safeIndex = Math.clamp(index - 1, 0, packs.size() - 1);
        return Component.literal(packs.get(safeIndex).displayName());
    }
}
