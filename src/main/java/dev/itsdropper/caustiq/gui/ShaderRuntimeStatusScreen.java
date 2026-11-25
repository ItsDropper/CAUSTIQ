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

    /** Keep the world ticking and rendering so compilation progress remains live behind this screen. */
    @Override
    public boolean isPauseScreen() {
        return false;
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
        reloadButton.setMessage(Component.translatable(status.isBusy()
                ? "caustiq.runtime_status.working" : "caustiq.runtime_status.reload"));
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
                if (line.progress() >= 0.0) {
                    int barWidth = Math.max(1, width - 48);
                    int filledWidth = (int) Math.round(barWidth * line.progress());
                    graphics.fill(16, y, 16 + barWidth, y + 6, 0xFF333333);
                    if (filledWidth > 0) {
                        graphics.fill(16, y, 16 + filledWidth, y + 6, 0xFF88CCFF);
                    }
                    y += 12;
                } else {
                    y = drawWrapped(graphics, line.text(), 16, y, Math.max(1, width - 32), line.color());
                }
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
        String stateSuffix = status.state().name().toLowerCase(Locale.ROOT);
        lines.add(new ContentLine(Component.translatable(
                "caustiq.runtime_status.state." + stateSuffix), stateColor(status.state()), 0));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.runtime_status.guidance." + stateSuffix), 0xFFCCCCCC, 4));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.runtime_status.pack", Component.literal(status.packName())), 0xFFFFFFFF, 8));

        if (status.totalPrograms() > 0) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.progress",
                    status.completedPrograms(), status.totalPrograms(), status.failedProgramCount()),
                    0xFFCCCCCC, 8));
            lines.add(ContentLine.progress(status.progressFraction(), 2));
        }
        if (!status.currentProgram().isBlank()) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.current_program",
                    Component.literal(status.currentProgram())), 0xFF88CCFF, 2));
        }
        if (status.state() == ShaderRuntimeStatus.State.FAILED
                || (status.state() == ShaderRuntimeStatus.State.READY_WITH_WARNINGS
                && status.failedProgramCount() == 0)) {
            lines.add(new ContentLine(Component.translatable(
                    "caustiq.runtime_status.details_heading"), 0xFFFFFFFF, 10));
            if (!status.message().isBlank()) {
                lines.add(new ContentLine(Component.literal(status.message()), 0xFFFF9999, 2));
            }
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
            case READY_WITH_WARNINGS, PREPARING, PREPARED, DISCOVERING, COMPILING ->
                    0xFFFFBB66;
            case FAILED, UNAVAILABLE -> 0xFFFF8888;
            case DISABLED, NO_PACK, WAITING_FOR_WORLD -> 0xFFAAAAAA;
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

    private record ContentLine(Component text, int color, int gapBefore, double progress) {
        private ContentLine(Component text, int color, int gapBefore) {
            this(text, color, gapBefore, -1.0);
        }

        private static ContentLine progress(double fraction, int gapBefore) {
            return new ContentLine(null, 0, gapBefore, Math.clamp(fraction, 0.0, 1.0));
        }
    }
}
