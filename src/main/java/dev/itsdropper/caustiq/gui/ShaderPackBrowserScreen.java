package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackFilter;
import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Searchable, keyboard-accessible browser for discovered shader packs. */
public final class ShaderPackBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 23;
    private final Screen parent;
    private String query = "";
    private int page;
    private EditBox searchField;

    public ShaderPackBrowserScreen(Screen parent) {
        super(Component.translatable("caustiq.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int left = width / 2 - 155;
        searchField = new EditBox(font, left, 44, 194, 20,
                Component.translatable("caustiq.browser.search_label"));
        searchField.setMaxLength(80);
        searchField.setHint(Component.translatable("caustiq.browser.search_hint"));
        searchField.setValue(query);
        addRenderableWidget(searchField);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.search"),
                button -> applySearch()).bounds(left + 198, 44, 54, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.clear"),
                button -> {
                    query = "";
                    page = 0;
                    init();
                    searchField.setFocused(true);
                }).bounds(left + 256, 44, 54, 20).build());

        Button refresh = Button.builder(Component.translatable("caustiq.browser.rescan"),
                button -> CaustiqClient.refreshShaderPacks()).bounds(left, 70, 310, 20).build();
        refresh.active = !CaustiqClient.refreshStatus().isScanning();
        addRenderableWidget(refresh);

        List<ShaderPackDescriptor> packs = filteredPacks();
        int pages = pageCount(packs.size());
        page = Math.clamp(page, 0, pages - 1);
        int pageSize = pageSize();
        int first = page * pageSize;
        int last = Math.min(first + pageSize, packs.size());
        for (int index = first; index < last; index++) {
            ShaderPackDescriptor pack = packs.get(index);
            boolean selected = pack.id().equals(CaustiqConfig.get().selectedPackId());
            Component source = Component.translatable(pack.sourceType() == ShaderPackSourceType.ZIP_ARCHIVE
                    ? "caustiq.settings.pack_source_archive" : "caustiq.settings.pack_source_directory");
            Component label = Component.translatable("caustiq.browser.pack_entry",
                    selected ? "✓ " : "", pack.displayName(), source, pack.shaderFileCount());
            int rowY = 98 + (index - first) * ROW_HEIGHT;
            addRenderableWidget(Button.builder(label, button -> select(pack))
                    .bounds(left, rowY, 248, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.inspect"),
                    button -> Minecraft.getInstance().setScreen(new ShaderPackInspectorScreen(this, pack)))
                    .bounds(left + 252, rowY, 58, 20).build());
        }

        int navY = height - 56;
        Button previous = Button.builder(Component.translatable("caustiq.browser.previous"),
                button -> changePage(-1)).bounds(left, navY, 100, 20).build();
        Button next = Button.builder(Component.translatable("caustiq.browser.next"),
                button -> changePage(1)).bounds(left + 210, navY, 100, 20).build();
        previous.active = page > 0;
        next.active = page + 1 < pages;
        addRenderableWidget(previous);
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
        searchField.setFocused(true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);
        List<ShaderPackDescriptor> packs = filteredPacks();
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        graphics.drawCenteredString(font, Component.translatable("caustiq.browser.count",
                packs.size(), scan == null ? 0 : scan.packs().size()), width / 2, 84, 0xFFCCCCCC);
        if (packs.isEmpty()) {
            Component empty = scan == null || scan.packs().isEmpty()
                    ? Component.translatable("caustiq.browser.empty")
                    : Component.translatable("caustiq.browser.no_matches", query);
            drawWrapped(graphics, empty, 120, width - 36, 0xFFAAAAAA);
        } else {
            graphics.drawCenteredString(font, Component.translatable("caustiq.browser.page",
                    page + 1, pageCount(packs.size())), width / 2, height - 70, 0xFFAAAAAA);
        }
        ShaderPackRefreshStatus status = CaustiqClient.refreshStatus();
        Component statusText = switch (status.state()) {
            case IDLE -> null;
            case SCANNING -> Component.translatable("caustiq.browser.scanning");
            case COMPLETE -> Component.translatable("caustiq.browser.scan_complete",
                    status.packCount(), status.rejectedCandidateCount());
            case FAILED -> Component.translatable("caustiq.browser.scan_failed", status.failureMessage());
        };
        if (statusText != null) {
            int color = status.state() == ShaderPackRefreshStatus.State.FAILED ? 0xFFFF7777
                    : status.state() == ShaderPackRefreshStatus.State.SCANNING ? 0xFFFFAA00 : 0xFF88DD88;
            drawWrapped(graphics, statusText, height - 88, width - 36, color);
        }
    }

    public void refreshView() {
        init();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void applySearch() {
        query = searchField.getValue().strip();
        page = 0;
        init();
        searchField.setFocused(true);
    }

    private List<ShaderPackDescriptor> filteredPacks() {
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        return scan == null ? List.of() : ShaderPackFilter.filter(scan.packs(), query);
    }

    private int pageSize() {
        return Math.max(1, Math.min(8, (height - 190) / ROW_HEIGHT));
    }

    private int pageCount(int resultCount) {
        return Math.max(1, (resultCount + pageSize() - 1) / pageSize());
    }

    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pageCount(filteredPacks().size()) - 1);
        init();
    }

    private void select(ShaderPackDescriptor pack) {
        CaustiqConfig.get().selectPack(pack.id());
        ShaderRuntime.requestReload();
        if (parent instanceof CaustiqSettingsScreen settings) settings.refreshView();
        Minecraft.getInstance().setScreen(parent);
    }

    private void drawWrapped(GuiGraphics graphics, Component component, int y, int maxWidth, int color) {
        List<FormattedCharSequence> lines = font.split(component, maxWidth);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawCenteredString(font, lines.get(index), width / 2, y + index * 10, color);
        }
    }
}
