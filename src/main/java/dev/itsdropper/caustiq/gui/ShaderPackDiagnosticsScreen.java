package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runtime and shader-pack discovery diagnostics with actionable, path-safe guidance. */
public final class ShaderPackDiagnosticsScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 3;
    private static final int NORMAL_ISSUE_ROW_HEIGHT = 68;
    private static final int COMPACT_ISSUE_ROW_HEIGHT = 52;

    private final Screen parent;
    private int page;
    private CaustiqDiagnostics.Snapshot snapshot;
    private ShaderPackScanResult displayedScan;
    private Button previousButton;
    private Button nextButton;
    private Button copyButton;

    public ShaderPackDiagnosticsScreen(Screen parent) {
        super(Component.translatable("caustiq.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        if (snapshot == null) snapshot = CaustiqDiagnostics.createSnapshot();
        if (displayedScan == null) displayedScan = CaustiqClient.scanResult();

        int pages = pageCount();
        page = Math.clamp(page, 0, pages - 1);
        int x = width / 2 - 155;
        int y = height - 56;

        previousButton = Button.builder(Component.translatable("caustiq.diagnostics.previous"),
                button -> changePage(-1))
                .bounds(x, y, 64, 20).build();
        nextButton = Button.builder(Component.translatable("caustiq.diagnostics.next"),
                button -> changePage(1))
                .bounds(x + 68, y, 64, 20).build();
        previousButton.active = page > 0;
        nextButton.active = page + 1 < pages;
        addRenderableWidget(previousButton);
        addRenderableWidget(nextButton);

        copyButton = Button.builder(Component.translatable("caustiq.diagnostics.copy"), button -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(CaustiqDiagnostics.createReport());
            button.setMessage(Component.translatable("caustiq.diagnostics.copied"));
        }).bounds(x + 136, y, 84, 20).build();
        addRenderableWidget(copyButton);

        Button retry = Button.builder(Component.translatable("caustiq.diagnostics.retry"),
                button -> CaustiqClient.refreshShaderPacks())
                .bounds(x + 224, y, 86, 20).build();
        retry.active = !CaustiqClient.refreshStatus().isScanning();
        addRenderableWidget(retry);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.diagnostics.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());

        setFocused(copyButton);
    }

    public void refreshView() {
        ShaderPackScanResult latest = CaustiqClient.scanResult();
        if (latest != displayedScan) {
            displayedScan = latest;
            page = 0;
        }
        snapshot = CaustiqDiagnostics.createSnapshot();
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);

        int y = 42;
        for (OverviewLine line : overviewLines()) {
            y = drawWrapped(graphics, line.text(), 16, y, width - 32, line.color()) + 2;
        }

        if (displayedScan == null) {
            drawWrapped(graphics, Component.translatable("caustiq.diagnostics.no_results"),
                    16, y + 8, width - 32, 0xFFAAAAAA);
            return;
        }

        if (displayedScan.issues().isEmpty()) {
            Component empty = displayedScan.candidatesInspected() == 0 && displayedScan.packs().isEmpty()
                    ? Component.translatable("caustiq.diagnostics.empty")
                    : Component.translatable("caustiq.diagnostics.no_rejections");
            drawWrapped(graphics, empty, 16, y + 8, width - 32, 0xFFAAAAAA);
            return;
        }

        y = drawWrapped(graphics,
                Component.translatable("caustiq.diagnostics.rejected_heading", displayedScan.issues().size()),
                16, y + 8, width - 32, 0xFFFFFFFF);
        int pageSize = pageSize();
        int start = page * pageSize;
        int end = Math.min(start + pageSize, displayedScan.issues().size());
        int rowHeight = issueRowHeight();
        for (int index = start; index < end; index++) {
            ShaderPackIssue issue = displayedScan.issues().get(index);
            int rowY = y + 4 + (index - start) * rowHeight;
            drawIssue(graphics, issue, rowY);
        }

        graphics.drawCenteredString(font, Component.translatable("caustiq.diagnostics.page",
                page + 1, pageCount()), width / 2, height - 70, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private List<OverviewLine> overviewLines() {
        List<OverviewLine> lines = new ArrayList<>();
        if (height >= 340) {
            lines.add(new OverviewLine(Component.translatable("caustiq.diagnostics.runtime",
                    snapshot.caustiqVersion(), snapshot.minecraftVersion(), snapshot.javaVersion()), 0xFFAAAAAA));
        }
        lines.add(new OverviewLine(Component.translatable("caustiq.diagnostics.renderer",
                Component.translatable(rendererStackKey())), 0xFFFFFFFF));

        boolean rendererReady = "SODIUM_WITH_NVIDIUM".equals(snapshot.rendererStack());
        lines.add(new OverviewLine(Component.translatable(rendererGuidanceKey()),
                rendererReady ? 0xFF77DD88 : 0xFFFFBB66));

        Component shaderState = Component.translatable(snapshot.shadersEnabled()
                ? "caustiq.diagnostics.shaders_enabled" : "caustiq.diagnostics.shaders_disabled");
        lines.add(new OverviewLine(Component.translatable("caustiq.diagnostics.shader_state",
                shaderState, Component.literal(snapshot.selectedPack())), 0xFFFFFFFF));
        if (!snapshot.shadersEnabled() && height >= 340) {
            lines.add(new OverviewLine(
                    Component.translatable("caustiq.diagnostics.shaders_disabled_hint"), 0xFFAAAAAA));
        } else if (isNoPackSelected() && height >= 340) {
            lines.add(new OverviewLine(
                    Component.translatable("caustiq.diagnostics.no_selected_pack_hint"), 0xFFAAAAAA));
        }

        if (!snapshot.scanCompleted()) {
            lines.add(new OverviewLine(
                    Component.translatable("caustiq.diagnostics.scan_not_completed"), 0xFFFFBB66));
        } else {
            lines.add(new OverviewLine(Component.translatable("caustiq.diagnostics.scan_summary",
                    snapshot.packCount(), snapshot.rejectedCount(), snapshot.candidateCount()), 0xFFCCCCCC));
            if ("SCANNING".equals(snapshot.latestRefreshState())) {
                lines.add(new OverviewLine(
                        Component.translatable("caustiq.diagnostics.scanning_previous"), 0xFFFFBB66));
            } else if ("FAILED".equals(snapshot.latestRefreshState())) {
                lines.add(new OverviewLine(Component.translatable("caustiq.diagnostics.failure_previous",
                        snapshot.latestRefreshFailureMessage()), 0xFFFF7777));
            }
        }
        return List.copyOf(lines);
    }

    private String rendererStackKey() {
        return switch (snapshot.rendererStack()) {
            case "SODIUM_WITH_NVIDIUM" -> "caustiq.diagnostics.renderer.sodium_nvidium";
            case "SODIUM" -> "caustiq.diagnostics.renderer.sodium";
            case "INVALID_NVIDIUM_WITHOUT_SODIUM" ->
                    "caustiq.diagnostics.renderer.nvidium_without_sodium";
            default -> "caustiq.diagnostics.renderer.vanilla";
        };
    }

    private String rendererGuidanceKey() {
        return switch (snapshot.rendererStack()) {
            case "SODIUM_WITH_NVIDIUM" -> "caustiq.diagnostics.guidance.ready";
            case "SODIUM" -> "caustiq.diagnostics.guidance.missing_nvidium";
            case "INVALID_NVIDIUM_WITHOUT_SODIUM" -> "caustiq.diagnostics.guidance.missing_sodium";
            default -> "caustiq.diagnostics.guidance.missing_both";
        };
    }

    private boolean isNoPackSelected() {
        return "None".equalsIgnoreCase(snapshot.selectedPack())
                || "Not available".equalsIgnoreCase(snapshot.selectedPack());
    }

    private void drawIssue(GuiGraphics graphics, ShaderPackIssue issue, int y) {
        int maxLines = width < 360 ? 1 : 2;
        int x = 16;
        int maxWidth = width - 32;
        int cursor = drawLimitedWrapped(graphics, Component.literal(issue.displayName()),
                x, y, maxWidth, 0xFFFFFFFF, 1) + 1;
        cursor = drawLimitedWrapped(graphics, Component.literal(issue.displayReason()),
                x, cursor, maxWidth, 0xFFBBBBBB, maxLines) + 1;

        CaustiqDiagnostics.IssueKind kind = CaustiqDiagnostics.classifyIssue(issue.displayReason());
        String suffix = kind.name().toLowerCase(Locale.ROOT);
        Component type = Component.translatable("caustiq.diagnostics.issue_type",
                Component.translatable("caustiq.diagnostics.issue_type." + suffix));
        cursor = drawLimitedWrapped(graphics, type, x, cursor, maxWidth, 0xFF999999, 1) + 1;

        Component guidance = Component.translatable("caustiq.diagnostics.issue_guidance." + suffix);
        drawLimitedWrapped(graphics, guidance, x, cursor, maxWidth, 0xFF88CCFF, maxLines);
    }

    private int pageSize() {
        int estimatedBottom = overviewBottom();
        int available = height - 80 - estimatedBottom;
        return Math.max(1, Math.min(MAX_PAGE_SIZE, available / issueRowHeight()));
    }

    private int overviewBottom() {
        int y = 42;
        for (OverviewLine line : overviewLines()) {
            y += Math.max(1, font.split(line.text(), width - 32).size()) * 10 + 2;
        }
        return y + 22;
    }

    private int issueRowHeight() {
        return width < 360 ? COMPACT_ISSUE_ROW_HEIGHT : NORMAL_ISSUE_ROW_HEIGHT;
    }

    private int pageCount() {
        int issueCount = displayedScan == null ? 0 : displayedScan.issues().size();
        int size = pageSize();
        return Math.max(1, (issueCount + size - 1) / size);
    }

    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pageCount() - 1);
        init();
        Button navigationTarget = direction < 0 ? previousButton : nextButton;
        setFocused(navigationTarget.active ? navigationTarget : copyButton);
    }

    private int drawWrapped(
            GuiGraphics graphics,
            Component message,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        for (FormattedCharSequence line : font.split(message, maxWidth)) {
            graphics.drawString(font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    private int drawLimitedWrapped(
            GuiGraphics graphics,
            Component message,
            int x,
            int y,
            int maxWidth,
            int color,
            int maxLines
    ) {
        List<FormattedCharSequence> lines = font.split(message, maxWidth);
        int visibleLines = Math.min(maxLines, lines.size());
        for (int index = 0; index < visibleLines; index++) {
            graphics.drawString(font, lines.get(index), x, y, color);
            y += 10;
        }
        return y;
    }

    private record OverviewLine(Component text, int color) { }
}
