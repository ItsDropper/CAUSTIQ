package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Paginated, path-safe explanation of candidates rejected by the last shader-pack scan. */
public final class ShaderPackDiagnosticsScreen extends Screen {
    private static final int MAX_PAGE_SIZE = 3;
    private static final int ISSUE_ROW_HEIGHT = 54;
    private final Screen parent;
    private int page;

    public ShaderPackDiagnosticsScreen(Screen parent) {
        super(Component.translatable("caustiq.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int pages = pageCount();
        page = Math.clamp(page, 0, pages - 1);
        int x = width / 2 - 155;
        int y = height - 56;

        Button previous = Button.builder(Component.translatable("caustiq.diagnostics.previous"),
                button -> changePage(-1))
                .bounds(x, y, 64, 20).build();
        Button next = Button.builder(Component.translatable("caustiq.diagnostics.next"),
                button -> changePage(1))
                .bounds(x + 68, y, 64, 20).build();
        previous.active = pages > 1;
        next.active = pages > 1;
        addRenderableWidget(previous);
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.diagnostics.copy"), button -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(CaustiqDiagnostics.createReport());
            button.setMessage(Component.translatable("caustiq.diagnostics.copied"));
        }).bounds(x + 136, y, 84, 20).build());
        Button retry = Button.builder(Component.translatable("caustiq.diagnostics.retry"),
                button -> CaustiqClient.refreshShaderPacks())
                .bounds(x + 224, y, 86, 20).build();
        retry.active = !CaustiqClient.refreshStatus().isScanning();
        addRenderableWidget(retry);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.diagnostics.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    public void refreshView() {
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 22, 0xFFFFFFFF);

        ShaderPackScanResult scan = CaustiqClient.scanResult();
        ShaderPackRefreshStatus status = CaustiqClient.refreshStatus();
        int y = 42;
        if (scan == null) {
            y = drawWrapped(graphics, Component.translatable("caustiq.diagnostics.no_results"),
                    16, y, width - 32, 0xFFCCCCCC);
            if (status.state() == ShaderPackRefreshStatus.State.SCANNING) {
                drawWrapped(graphics, Component.translatable("caustiq.diagnostics.scanning"),
                        16, y + 8, width - 32, 0xFFFFAA00);
            } else if (status.state() == ShaderPackRefreshStatus.State.FAILED) {
                drawWrapped(graphics, Component.translatable("caustiq.diagnostics.failure",
                        status.failureMessage()), 16, y + 8, width - 32, 0xFFFF7777);
            }
            return;
        }

        y = drawWrapped(graphics, Component.translatable("caustiq.diagnostics.summary",
                scan.candidatesInspected(), scan.packs().size(), scan.issues().size()),
                16, y, width - 32, 0xFFCCCCCC);
        if (status.state() == ShaderPackRefreshStatus.State.SCANNING) {
            y = drawWrapped(graphics, Component.translatable("caustiq.diagnostics.scanning_previous"),
                    16, y + 4, width - 32, 0xFFFFAA00);
        } else if (status.state() == ShaderPackRefreshStatus.State.FAILED) {
            y = drawWrapped(graphics, Component.translatable("caustiq.diagnostics.failure_previous",
                    status.failureMessage()), 16, y + 4, width - 32, 0xFFFF7777);
        }

        if (scan.issues().isEmpty()) {
            Component empty = scan.candidatesInspected() == 0 && scan.packs().isEmpty()
                    ? Component.translatable("caustiq.diagnostics.empty")
                    : Component.translatable("caustiq.diagnostics.no_rejections");
            drawWrapped(graphics, empty, 16, y + 12, width - 32, 0xFFAAAAAA);
            return;
        }

        int pageSize = pageSize();
        int start = page * pageSize;
        int end = Math.min(start + pageSize, scan.issues().size());
        for (int index = start; index < end; index++) {
            ShaderPackIssue issue = scan.issues().get(index);
            int row = y + 14 + (index - start) * ISSUE_ROW_HEIGHT;
            graphics.drawString(font, Component.literal(issue.displayName()), 16, row, 0xFFFFFFFF);
            var reasonLines = font.split(Component.literal(issue.displayReason()), width - 32);
            int visibleLines = Math.min(3, reasonLines.size());
            for (int line = 0; line < visibleLines; line++) {
                graphics.drawString(font, reasonLines.get(line), 16, row + 12 + line * 10, 0xFFBBBBBB);
            }
            if (reasonLines.size() > visibleLines) {
                graphics.drawString(font, Component.literal("..."), 16,
                        row + 12 + visibleLines * 10, 0xFF888888);
            }
        }
        graphics.drawCenteredString(font, Component.translatable("caustiq.diagnostics.page",
                page + 1, pageCount()), width / 2, height - 70, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private int pageSize() {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, (height - 150) / ISSUE_ROW_HEIGHT));
    }

    private int pageCount() {
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        int issueCount = scan == null ? 0 : scan.issues().size();
        return Math.max(1, (issueCount + pageSize() - 1) / pageSize());
    }

    private void changePage(int direction) {
        int pages = pageCount();
        page = Math.floorMod(page + direction, pages);
        init();
    }

    private int drawWrapped(
            GuiGraphics graphics,
            Component message,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        for (var line : font.split(message, maxWidth)) {
            graphics.drawString(font, line, x, y, color);
            y += 10;
        }
        return y;
    }
}
