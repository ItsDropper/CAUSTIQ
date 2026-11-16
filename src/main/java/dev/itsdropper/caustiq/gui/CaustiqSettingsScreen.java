package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackMetadata;
import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSelection;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSources;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
        Button previousPack = Button.builder(Component.translatable("caustiq.settings.previous_pack"),
                button -> movePackSelection(packs, config, -1))
                .bounds(width / 2 + 5, 42, 70, 20).build();
        Button nextPack = Button.builder(Component.translatable("caustiq.settings.next_pack"),
                button -> movePackSelection(packs, config, 1))
                .bounds(width / 2 + 85, 42, 70, 20).build();
        previousPack.active = !packs.isEmpty();
        nextPack.active = !packs.isEmpty();
        addRenderableWidget(previousPack);
        addRenderableWidget(nextPack);

        ShaderPackRefreshStatus refreshStatus = CaustiqClient.refreshStatus();
        Button refreshButton = Button.builder(refreshButtonLabel(refreshStatus),
                button -> CaustiqClient.refreshShaderPacks())
                .bounds(width / 2 - 155, 68, 150, 20).build();
        refreshButton.active = !refreshStatus.isScanning();
        addRenderableWidget(refreshButton);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.copy_diagnostics"),
                button -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(CaustiqDiagnostics.createReport());
                    button.setMessage(Component.translatable("caustiq.settings.diagnostics_copied"));
                }).bounds(width / 2 + 5, 68, 150, 20).build());

        if (selected != null) addOptionButtons(selected, config);
        else addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.no_selection"),
                button -> { }).bounds(width / 2 - 155, 164, 310, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    public void refreshView() { init(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        List<ShaderPackDescriptor> packs = scan == null ? List.of() : scan.packs();
        ShaderPackDescriptor selected = packs.stream()
                .filter(pack -> pack.id().equals(CaustiqConfig.get().selectedPackId()))
                .findFirst().orElse(null);
        Component packName = selected == null
                ? Component.translatable(packs.isEmpty()
                        ? "caustiq.settings.no_packs" : "caustiq.settings.select_pack")
                : Component.literal(selected.displayName());
        graphics.drawCenteredString(font, packName, width / 2 + 80, 48, 0xFFFFFFFF);
        if (selected != null) {
            graphics.drawCenteredString(font, Component.translatable("caustiq.settings.pack_summary",
                    sourceTypeLabel(selected), selected.shaderFileCount()),
                    width / 2 + 80, 108, 0xFFCCCCCC);
            graphics.drawCenteredString(font, Component.translatable("caustiq.settings.pack_detail",
                    selected.pipelineEntryPointCount(), formatSize(selected.declaredUncompressedBytes())),
                    width / 2 + 80, 120, 0xFFAAAAAA);
            graphics.drawCenteredString(font, Component.translatable("caustiq.settings.structural_only"),
                    width / 2 + 80, 132, 0xFF888888);
            Component metadataSummary = metadataLoaded && selected.id().equals(metadataCachePackId)
                    ? Component.translatable("caustiq.settings.pack_options_count", metadataCache == null
                            ? Component.translatable("caustiq.settings.pack_options_unavailable")
                            : Integer.toString(metadataCache.options().size()))
                    : Component.translatable("caustiq.settings.pack_options_loading");
            graphics.drawCenteredString(font, metadataSummary, width / 2 + 80, 144, 0xFFAAAAAA);
        }

        ShaderPackRefreshStatus status = CaustiqClient.refreshStatus();
        Component message = refreshStatusMessage(status);
        if (message != null) {
            int color = switch (status.state()) {
                case SCANNING -> 0xFFFFAA00;
                case COMPLETE -> 0xFF55FF55;
                case FAILED -> 0xFFFF5555;
                case IDLE -> 0xFFFFFFFF;
            };
            graphics.drawCenteredString(font, message, width / 2, 92, color);
        }
    }

    private static Component refreshButtonLabel(ShaderPackRefreshStatus status) {
        return switch (status.state()) {
            case SCANNING -> Component.translatable("caustiq.settings.scanning");
            case FAILED -> Component.translatable("caustiq.settings.retry_scan");
            case IDLE, COMPLETE -> Component.translatable("caustiq.settings.refresh");
        };
    }

    private static Component refreshStatusMessage(ShaderPackRefreshStatus status) {
        return switch (status.state()) {
            case IDLE -> null;
            case SCANNING -> Component.translatable("caustiq.settings.scan_in_progress");
            case COMPLETE -> Component.translatable("caustiq.settings.scan_complete",
                    status.packCount(), status.rejectedCandidateCount());
            case FAILED -> Component.translatable("caustiq.settings.scan_failed");
        };
    }

    private void movePackSelection(List<ShaderPackDescriptor> packs, CaustiqConfig config, int direction) {
        int next = ShaderPackSelection.nextIndex(packs, config.selectedPackId(), direction);
        if (next < 0) return;
        config.selectPack(packs.get(next).id());
        optionPage = 0;
        ShaderRuntime.requestReload();
        init();
    }

    private static Component sourceTypeLabel(ShaderPackDescriptor pack) {
        return Component.translatable(pack.sourceType() == dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType.DIRECTORY
                ? "caustiq.settings.pack_source_directory" : "caustiq.settings.pack_source_archive");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private void addOptionButtons(ShaderPackDescriptor pack, CaustiqConfig config) {
        ShaderPackMetadata metadata = readMetadata(pack);
        if (metadata == null || metadata.options().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("caustiq.settings.no_options"),
                    button -> { }).bounds(width / 2 - 155, 152, 310, 20).build());
            return;
        }
        int pages = (metadata.options().size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int start = optionPage * PAGE_SIZE;
        if (start >= metadata.options().size()) {
            optionPage = 0;
            start = 0;
        }
        int end = Math.min(start + PAGE_SIZE, metadata.options().size());
        int y = 164;
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
