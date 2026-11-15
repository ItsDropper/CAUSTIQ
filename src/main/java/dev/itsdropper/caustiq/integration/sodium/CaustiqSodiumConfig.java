package dev.itsdropper.caustiq.integration.sodium;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackMetadata;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import java.io.IOException;
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
    private static final Identifier SHADER_OPTION = Identifier.fromNamespaceAndPath("caustiq", "shader_option");
    private static final Identifier SHADER_OPTION_VALUE = Identifier.fromNamespaceAndPath("caustiq", "shader_option_value");
    private static String metadataCachePackId;
    private static ShaderPackMetadata metadataCache;

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
                                .addOption(builder.createIntegerOption(SHADER_OPTION)
                                        .setName(Component.translatable("caustiq.settings.shader_option"))
                                        .setTooltip(Component.translatable("caustiq.settings.shader_option_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(CaustiqSodiumConfig::selectShaderOptionAtIndex,
                                                CaustiqSodiumConfig::selectedShaderOptionIndex)
                                        .setRangeProvider(ignored -> new Range(0,
                                                Math.max(0, availableOptions().size() - 1), 1),
                                                PACK, ConfigState.UPDATE_ON_REBUILD)
                                        .setValueFormatter(CaustiqSodiumConfig::formatShaderOptionIndex)
                                        .setDefaultValue(0))
                                .addOption(builder.createIntegerOption(SHADER_OPTION_VALUE)
                                        .setName(Component.translatable("caustiq.settings.shader_option_value"))
                                        .setTooltip(Component.translatable("caustiq.settings.shader_option_value_tooltip"))
                                        .setStorageHandler(() -> { })
                                        .setBinding(CaustiqSodiumConfig::selectShaderOptionValueAtIndex,
                                                CaustiqSodiumConfig::selectedShaderOptionValueIndex)
                                        .setRangeProvider(ignored -> new Range(0,
                                                Math.max(0, currentShaderOptionValues().size() - 1), 1),
                                                PACK, SHADER_OPTION, ConfigState.UPDATE_ON_REBUILD)
                                        .setValueFormatter(CaustiqSodiumConfig::formatShaderOptionValueIndex)
                                        .setApplyHook(ignored -> ShaderRuntime.requestReload())
                                        .setDefaultValue(0))
                                .addOption(builder.createExternalButtonOption(REFRESH)
                                        .setName(Component.translatable("caustiq.settings.refresh"))
                                        .setTooltip(Component.translatable("caustiq.settings.refresh_tooltip"))
                                        .setScreenConsumer(ignored -> {
                                            metadataCachePackId = null;
                                            metadataCache = null;
                                            CaustiqClient.refreshShaderPacks();
                                        }))));
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


    private static ShaderPackDescriptor selectedPack() {
        String selectedId = CaustiqConfig.get().selectedPackId();
        return availablePacks().stream().filter(pack -> pack.id().equals(selectedId)).findFirst().orElse(null);
    }

    private static ShaderPackMetadata selectedPackMetadata() {
        ShaderPackDescriptor pack = selectedPack();
        if (pack == null) return null;
        if (pack.id().equals(metadataCachePackId)) return metadataCache;
        metadataCachePackId = pack.id();
        metadataCache = null;
        try (ShaderPackSources sources = ShaderPackSources.open(pack)) {
            String properties;
            try {
                properties = sources.read("shaders.properties");
            } catch (IOException missingProperties) {
                properties = "";
            }
            metadataCache = ShaderPackMetadata.parse(properties, sources);
        } catch (IOException | IllegalArgumentException exception) {
            CaustiqClient.LOGGER.warn("Unable to read options for shader pack '{}': {}",
                    pack.displayName(), exception.getMessage());
        }
        return metadataCache;
    }

    private static List<ShaderPackMetadata.Option> availableOptions() {
        ShaderPackMetadata metadata = selectedPackMetadata();
        return metadata == null ? List.of() : metadata.options();
    }

    private static int selectedShaderOptionIndex() {
        String selectedName = CaustiqConfig.get().selectedOptionName();
        List<ShaderPackMetadata.Option> options = availableOptions();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).name().equals(selectedName)) return i;
        }
        return 0;
    }

    private static void selectShaderOptionAtIndex(int index) {
        List<ShaderPackMetadata.Option> options = availableOptions();
        if (options.isEmpty()) return;
        int safeIndex = Math.clamp(index, 0, options.size() - 1);
        CaustiqConfig.get().selectOption(options.get(safeIndex).name());
    }

    private static ShaderPackMetadata.Option selectedShaderOption() {
        List<ShaderPackMetadata.Option> options = availableOptions();
        if (options.isEmpty()) return null;
        int index = Math.clamp(selectedShaderOptionIndex(), 0, options.size() - 1);
        return options.get(index);
    }

    private static List<String> currentShaderOptionValues() {
        ShaderPackMetadata.Option option = selectedShaderOption();
        return option == null ? List.of() : option.values();
    }

    private static int selectedShaderOptionValueIndex() {
        ShaderPackDescriptor pack = selectedPack();
        ShaderPackMetadata.Option option = selectedShaderOption();
        if (pack == null || option == null) return 0;
        String selectedValue = CaustiqConfig.get().option(pack.id(), option.name(), option.defaultValue());
        int index = option.values().indexOf(selectedValue);
        return index >= 0 ? index : Math.max(0, option.values().indexOf(option.defaultValue()));
    }

    private static void selectShaderOptionValueAtIndex(int index) {
        ShaderPackDescriptor pack = selectedPack();
        ShaderPackMetadata.Option option = selectedShaderOption();
        if (pack == null || option == null || option.values().isEmpty()) return;
        int safeIndex = Math.clamp(index, 0, option.values().size() - 1);
        CaustiqConfig.get().selectOption(option.name());
        CaustiqConfig.get().setOption(pack.id(), option.name(), option.values().get(safeIndex));
    }

    private static Component formatShaderOptionIndex(int index) {
        List<ShaderPackMetadata.Option> options = availableOptions();
        if (options.isEmpty()) return Component.translatable("caustiq.settings.no_options");
        int safeIndex = Math.clamp(index, 0, options.size() - 1);
        return Component.literal(options.get(safeIndex).label());
    }

    private static Component formatShaderOptionValueIndex(int index) {
        List<String> values = currentShaderOptionValues();
        if (values.isEmpty()) return Component.translatable("caustiq.settings.no_option_values");
        int safeIndex = Math.clamp(index, 0, values.size() - 1);
        return Component.literal(values.get(safeIndex));
    }

    private static Component formatPackIndex(int index) {
        List<ShaderPackDescriptor> packs = availablePacks();
        if (index <= 0) return Component.translatable("caustiq.settings.select_pack");
        if (packs.isEmpty()) return Component.translatable("caustiq.settings.no_packs");
        int safeIndex = Math.clamp(index - 1, 0, packs.size() - 1);
        return Component.literal(packs.get(safeIndex).displayName());
    }
}
