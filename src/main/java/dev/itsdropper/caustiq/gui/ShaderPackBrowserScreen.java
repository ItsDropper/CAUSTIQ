package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.shaderpack.ShaderPackBrowserModel;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
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

import java.util.ArrayList;
import java.util.List;

/** Live-searchable, filterable and keyboard-accessible browser for discovered shader packs. */
public final class ShaderPackBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 23;
    private static final int RESULTS_TOP = 142;
    private static final int MAX_PAGE_SIZE = 8;

    private final Screen parent;
    private final List<Button> resultButtons = new ArrayList<>();
    private String query = "";
    private int page;
    private ShaderPackBrowserModel.SourceFilter sourceFilter = ShaderPackBrowserModel.SourceFilter.ALL;
    private ShaderPackBrowserModel.SortOrder sortOrder = ShaderPackBrowserModel.SortOrder.NAME;
    private EditBox searchField;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button backButton;

    public ShaderPackBrowserScreen(Screen parent) {
        super(Component.translatable("caustiq.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        resultButtons.clear();
        int left = width / 2 - 155;

        searchField = new EditBox(font, left, 44, 258, 20,
                Component.translatable("caustiq.browser.search_label"));
        searchField.setMaxLength(80);
        searchField.setHint(Component.translatable("caustiq.browser.search_hint"));
        searchField.setValue(query);
        searchField.setResponder(value -> {
            query = value;
            page = 0;
            rebuildResults();
        });
        addRenderableWidget(searchField);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.clear"),
                button -> {
                    searchField.setValue("");
                    searchField.setFocused(true);
                }).bounds(left + 262, 44, 48, 20).build());

        addRenderableWidget(Button.builder(sourceFilterLabel(), button -> {
            sourceFilter = sourceFilter.next();
            page = 0;
            button.setMessage(sourceFilterLabel());
            rebuildResults();
        }).bounds(left, 70, 152, 20).build());
        addRenderableWidget(Button.builder(sortOrderLabel(), button -> {
            sortOrder = sortOrder.next();
            page = 0;
            button.setMessage(sortOrderLabel());
            rebuildResults();
        }).bounds(left + 160, 70, 150, 20).build());

        Button refresh = Button.builder(Component.translatable("caustiq.browser.rescan"),
                button -> CaustiqClient.refreshShaderPacks()).bounds(left, 96, 310, 20).build();
        refresh.active = !CaustiqClient.refreshStatus().isScanning();
        addRenderableWidget(refresh);

        previousPageButton = Button.builder(Component.translatable("caustiq.browser.previous"),
                button -> changePage(-1)).bounds(left, height - 56, 100, 20).build();
        nextPageButton = Button.builder(Component.translatable("caustiq.browser.next"),
                button -> changePage(1)).bounds(left + 210, height - 56, 100, 20).build();
        backButton = Button.builder(Component.translatable("caustiq.browser.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build();

        rebuildResults();
        searchField.setFocused(true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);

        ShaderPackScanResult scan = CaustiqClient.scanResult();
        List<ShaderPackDescriptor> packs = filteredPacks();
        graphics.drawCenteredString(font, Component.translatable("caustiq.browser.count",
                packs.size(), scan == null ? 0 : scan.packs().size()), width / 2, 124, 0xFFCCCCCC);
        if (packs.isEmpty()) {
            Component empty = scan == null || scan.packs().isEmpty()
                    ? Component.translatable("caustiq.browser.empty")
                    : Component.translatable("caustiq.browser.no_matches");
            drawWrapped(graphics, empty, RESULTS_TOP + 8, width - 36, 0xFFAAAAAA);
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
            drawWrapped(graphics, statusText, height - 100, width - 36, color);
        }
    }

    public void refreshView() {
        init();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private List<ShaderPackDescriptor> filteredPacks() {
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        return scan == null ? List.of()
                : ShaderPackBrowserModel.apply(scan.packs(), query, sourceFilter, sortOrder);
    }

    private void rebuildResults() {
        if (previousPageButton != null) removeWidget(previousPageButton);
        if (nextPageButton != null) removeWidget(nextPageButton);
        if (backButton != null) removeWidget(backButton);
        for (Button button : resultButtons) {
            removeWidget(button);
        }
        resultButtons.clear();

        List<ShaderPackDescriptor> packs = filteredPacks();
        int pages = pageCount(packs.size());
        page = Math.clamp(page, 0, pages - 1);
        int pageSize = pageSize();
        int first = page * pageSize;
        int last = Math.min(first + pageSize, packs.size());
        int left = width / 2 - 155;

        for (int index = first; index < last; index++) {
            ShaderPackDescriptor pack = packs.get(index);
            boolean selected = pack.id().equals(CaustiqConfig.get().selectedPackId());
            Component source = Component.translatable(pack.sourceType() == ShaderPackSourceType.ZIP_ARCHIVE
                    ? "caustiq.settings.pack_source_archive" : "caustiq.settings.pack_source_directory");
            Component label = Component.translatable("caustiq.browser.pack_entry",
                    selected ? "✓ " : "", pack.displayName(), source, pack.shaderFileCount());
            int rowY = RESULTS_TOP + (index - first) * ROW_HEIGHT;
            resultButtons.add(addRenderableWidget(Button.builder(label, button -> select(pack))
                    .bounds(left, rowY, 248, 20).build()));
            resultButtons.add(addRenderableWidget(Button.builder(Component.translatable("caustiq.browser.inspect"),
                    button -> Minecraft.getInstance().setScreen(new ShaderPackInspectorScreen(this, pack)))
                    .bounds(left + 252, rowY, 58, 20).build()));
        }

        if (previousPageButton != null) {
            previousPageButton.active = page > 0;
            addRenderableWidget(previousPageButton);
        }
        if (nextPageButton != null) {
            nextPageButton.active = page + 1 < pages;
            addRenderableWidget(nextPageButton);
        }
        if (backButton != null) addRenderableWidget(backButton);
    }

    private int pageSize() {
        int availableRows = (height - RESULTS_TOP - 124) / ROW_HEIGHT;
        return Math.max(1, Math.min(MAX_PAGE_SIZE, availableRows));
    }

    private int pageCount(int resultCount) {
        return Math.max(1, (resultCount + pageSize() - 1) / pageSize());
    }

    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pageCount(filteredPacks().size()) - 1);
        rebuildResults();
        Button navigationTarget = direction < 0 ? previousPageButton : nextPageButton;
        setFocused(navigationTarget.active ? navigationTarget : searchField);
    }

    private Component sourceFilterLabel() {
        Component value = Component.translatable(switch (sourceFilter) {
            case ALL -> "caustiq.browser.source_all";
            case FOLDERS -> "caustiq.browser.source_folders";
            case ZIP_ARCHIVES -> "caustiq.browser.source_archives";
        });
        return Component.translatable("caustiq.browser.source_filter", value);
    }

    private Component sortOrderLabel() {
        Component value = Component.translatable(switch (sortOrder) {
            case NAME -> "caustiq.browser.sort_name";
            case MOST_SHADERS -> "caustiq.browser.sort_shader_count";
            case LARGEST -> "caustiq.browser.sort_size";
        });
        return Component.translatable("caustiq.browser.sort_order", value);
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
