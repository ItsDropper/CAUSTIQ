package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackMetadata;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;

/** Renderer-independent shader-pack selection and option screen. */
public final class CaustiqSettingsScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private final Screen parent;
    private int optionPage;
    private String metadataCachePackId;
    private ShaderPackMetadata metadataCache;
    private boolean metadataLoaded;

    public CaustiqSettingsScreen(Screen parent) {
        super(Component.translatable("caustiq.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        CaustiqConfig config = CaustiqConfig.get();
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        addRenderableWidget(Button.builder(enabledLabel(config.enabled()), button -> {
            config.setEnabled(!config.enabled());
            ShaderRuntime.requestReload();
            init();
        }).bounds(width / 2 - 155, 42, 150, 20).build());

        List<ShaderPackDescriptor> packs = scan == null ? List.of() : scan.packs();
        ShaderPackDescriptor selected = packs.stream()
                .filter(pack -> pack.id().equals(config.selectedPackId()))
                .findFirst().orElse(null);
        String packLabel = selected == null
                ? (packs.isEmpty() ? "No shader packs found" : "Select shader pack")
                : selected.displayName();
        addRenderableWidget(Button.builder(Component.literal("Pack: " + packLabel), button -> {
            if (packs.isEmpty()) return;
            int current = selected == null ? -1 : packs.indexOf(selected);
            ShaderPackDescriptor next = packs.get((current + 1) % packs.size());
            config.selectPack(next.id());
            optionPage = 0;
            ShaderRuntime.requestReload();
            init();
        }).bounds(width / 2 + 5, 42, 150, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.refresh"),
                button -> CaustiqClient.refreshShaderPacks())
                .bounds(width / 2 - 155, 68, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.copy_diagnostics"),
                button -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(CaustiqDiagnostics.createReport());
                    button.setMessage(Component.translatable("caustiq.settings.diagnostics_copied"));
                }).bounds(width / 2 + 5, 68, 150, 20).build());

        if (selected != null) addOptionButtons(selected, config);
        else addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.no_selection"),
                button -> { }).bounds(width / 2 - 155, 104, 310, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    public void refreshView() { init(); }

    private void addOptionButtons(ShaderPackDescriptor pack, CaustiqConfig config) {
        ShaderPackMetadata metadata = readMetadata(pack);
        if (metadata == null || metadata.options().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.no_options"),
                    button -> { }).bounds(width / 2 - 155, 104, 310, 20).build());
            return;
        }
        int pages = (metadata.options().size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int start = optionPage * PAGE_SIZE;
        if (start >= metadata.options().size()) {
            optionPage = 0;
            start = 0;
        }
        int end = Math.min(start + PAGE_SIZE, metadata.options().size());
        int y = 104;
        for (ShaderPackMetadata.Option option : metadata.options().subList(start, end)) {
            String value = config.option(pack.id(), option.name(), option.defaultValue());
            int selectedIndex = option.values().indexOf(value);
            if (selectedIndex < 0) selectedIndex = option.values().indexOf(option.defaultValue());
            if (selectedIndex < 0) selectedIndex = 0;
            final int currentIndex = selectedIndex;
            addRenderableWidget(Button.builder(Component.literal(option.label() + ": " + value), button -> {
                int nextIndex = (currentIndex + 1) % option.values().size();
                config.setOption(pack.id(), option.name(), option.values().get(nextIndex));
                ShaderRuntime.requestReload();
                init();
            }).bounds(width / 2 - 155, y, 310, 20).build());
            y += 24;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("Options page " + (optionPage + 1) + "/" + pages),
                    button -> {
                        optionPage = (optionPage + 1) % pages;
                        init();
                    }).bounds(width / 2 - 155, y, 310, 20).build());
        }
    }

    private ShaderPackMetadata readMetadata(ShaderPackDescriptor pack) {
        if (metadataLoaded && pack.id().equals(metadataCachePackId)) return metadataCache;
        metadataLoaded = true;
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

    private static Component enabledLabel(boolean enabled) {
        return Component.translatable(enabled ? "caustiq.settings.enabled" : "caustiq.settings.disabled");
    }
}
