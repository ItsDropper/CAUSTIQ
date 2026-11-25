package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntimeStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Live, path-safe view of shader-pack preparation and program-processing status. */
public final class ShaderRuntimeStatusScreen extends Screen {
    private static final int CONTENT_TOP = 42;

    private final Screen parent;
    private final ShaderPackIssueScrollState scrollState = new ShaderPackIssueScrollState();
    private Button reloadButton;

    public ShaderRuntimeStatusScreen(Screen parent) {
        super(Component.translatable("caustiq.runtime_status.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    @Override
    protected void init() {
        clearWidgets();
        int buttonWidth = Math.max(48, Math.min(100, (width - 42) / 3));
        int gap = 5;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int left = Math.max(4, (width - totalWidth) / 2);
        int y = height - 28;

        Button copyButton = Button.builder(Component.translatable("caustiq.runtime_status.copy"), button -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(ShaderRuntime.status().createReport());
            button.setMessage(Component.translatable("caustiq.runtime_status.copied"));
        }).bounds(left, y, buttonWidth, 20).build();
        addRenderableWidget(copyButton);

        reloadButton = Button.builder(Component.translatable("caustiq.runtime_status.reload"), button -> {
            ShaderRuntime.requestReload();
            button.setMessage(Component.translatable("caustiq.runtime_status.reload_requested"));
        }).bounds(left + buttonWidth + gap, y, buttonWidth, 20).build();
        reloadButton.active = ShaderRuntime.status().canRetry();
        addRenderableWidget(reloadButton);

        addRenderableWidget(Button.builder(Component.translatable("caustiq.runtime_status.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        setFocused(copyButton);
        scrollState.setDimensions(0, viewportBottom() - CONTENT_TOP);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ShaderRuntimeStatus status = ShaderRuntime.status();
        reloadButton.active = status.canRetry();
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);

        List<ContentLine> lines = buildContent(status);
        int viewportBottom = viewportBottom();
        int viewportHeight = viewportBottom - CONTENT_TOP;
        scrollState.setDimensions(scrollState.contentHeight(), viewportHeight);
        int contentTop = CONTENT_TOP - scrollState.offset();

        graphics.enableScissor(8, CONTENT_TOP, Math.max(9, width - 8), viewportBottom);
        try {
            int y = contentTop;
            for (ContentLine line : lines) {
                y += line.gapBefore();
                y = drawWrapped(graphics, line.text(), 16, y, Math.max(1, width - 32), line.color());
            }
            scrollState.setDimensions(Math.max(0, y - contentTop), viewportHeight);
        } finally {
            graphics.disableScissor();
        }

        if (scrollState.maxOffset() > 0) {
            drawScrollbar(graphics, viewportBottom);
            graphics.drawCenteredString(font,
                    Component.translatable("caustiq.runtime_status.scroll_hint"),
                    width / 2, height - 40, 0xFF999999);
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount
    ) {
        if (mouseY < CONTENT_TOP || mouseY >= viewportBottom() || verticalAmount == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return scrollState.scrollWheel(verticalAmount, 18)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int pageStep = Math.max(18, viewportBottom() - CONTENT_TOP - 18);
        return scrollState.handleKey(event.key(), 18, pageStep) || super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private List<ContentLine> buildContent(ShaderRuntimeStatus status) {
        List<ContentLine> lines = new ArrayList<>();
        String stateKey = "caustiq.runtime_status.state."
                + status.state().name().toLowerCase(Locale.ROOT);
        lines.add(new ContentLine(Component.translatable(stateKey), stateColor(status.state()), 0));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.runtime_status.pack", Component.literal(status.packName())), 0xFFFFFFFF, 8));

        if (status.totalPrograms() > 0) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.progress",
                    status.completedPrograms(), status.totalPrograms(), status.failedProgramCount()),
                    0xFFCCCCCC, 8));
        }
        if (!status.currentProgram().isBlank()) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.current_program",
                    Component.literal(status.currentProgram())), 0xFF88CCFF, 2));
        }
        if (!status.message().isBlank()) {
            lines.add(new ContentLine(Component.literal(status.message()), 0xFFCCCCCC, 10));
        }

        if (status.failedProgramCount() > 0) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.failures", status.failedProgramCount()), 0xFFFFBB66, 12));
            for (ShaderRuntimeStatus.ProgramFailure failure : status.failures()) {
                lines.add(new ContentLine(Component.translatable(
                        "caustiq.runtime_status.failure_entry",
                        Component.literal(failure.programName()),
                        Component.literal(failure.exceptionType())), 0xFFFF9999, 2));
            }
            if (status.omittedFailureCount() > 0) {
                lines.add(new ContentLine(Component.translatable(
                        "caustiq.runtime_status.omitted", status.omittedFailureCount()), 0xFF999999, 2));
            }
        } else if (status.state() == ShaderRuntimeStatus.State.READY) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.no_failures"), 0xFF77DD88, 8));
        }

        lines.add(new ContentLine(Component.translatable(
                "caustiq.runtime_status.privacy"), 0xFF888888, 14));
        return List.copyOf(lines);
    }

    private static int stateColor(ShaderRuntimeStatus.State state) {
        return switch (state) {
            case READY -> 0xFF77DD88;
            case READY_WITH_WARNINGS, FAILED, UNAVAILABLE -> 0xFFFF8888;
            case PREPARING, PREPARED, DISCOVERING, COMPILING, WAITING_FOR_WORLD -> 0xFFFFBB66;
            case DISABLED, NO_PACK -> 0xFFAAAAAA;
        };
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

    private int viewportBottom() {
        return Math.max(CONTENT_TOP + 1, height - 44);
    }

    private void drawScrollbar(GuiGraphics graphics, int viewportBottom) {
        int trackHeight = viewportBottom - CONTENT_TOP;
        int thumbHeight = scrollState.thumbHeight(trackHeight, 14);
        int thumbY = CONTENT_TOP + scrollState.thumbOffset(trackHeight, thumbHeight);
        graphics.fill(width - 5, CONTENT_TOP, width - 3, viewportBottom, 0x66555555);
        graphics.fill(width - 5, thumbY, width - 3, thumbY + thumbHeight, 0xFF88CCFF);
    }

    private record ContentLine(Component text, int color, int gapBefore) { }
}
