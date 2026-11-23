package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics;
import dev.itsdropper.caustiq.diagnostics.CaustiqDiagnostics.IssueDetails;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Full, path-safe explanation of one rejected shader-pack candidate. */
public final class ShaderPackIssueDetailsScreen extends Screen {
    private static final int CONTENT_TOP = 42;

    private final Screen parent;
    private final IssueDetails issue;
    private List<ContentLine> content = List.of();
    private int scrollOffset;
    private int contentHeight;

    public ShaderPackIssueDetailsScreen(Screen parent, IssueDetails issue) {
        super(Component.translatable("caustiq.diagnostics.issue_details.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.issue = Objects.requireNonNull(issue, "issue");
    }

    @Override
    protected void init() {
        clearWidgets();
        content = buildContent();
        contentHeight = 0;

        Button copyButton = Button.builder(
                Component.translatable("caustiq.diagnostics.copy_issue"),
                button -> {
                    Component report = Component.translatable("caustiq.diagnostics.issue_report",
                            Component.literal(issue.candidateName()),
                            issueType(),
                            Component.literal(issue.reason()),
                            issueGuidance());
                    Minecraft.getInstance().keyboardHandler.setClipboard(report.getString());
                    button.setMessage(Component.translatable("caustiq.diagnostics.issue_copied"));
                }).bounds(width / 2 - 104, height - 28, 116, 20).build();
        addRenderableWidget(copyButton);
        addRenderableWidget(Button.builder(Component.translatable("caustiq.diagnostics.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 + 20, height - 28, 84, 20).build());
        setFocused(copyButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);

        int viewportBottom = viewportBottom();
        int viewportHeight = viewportBottom - CONTENT_TOP;
        scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, contentHeight - viewportHeight));

        graphics.enableScissor(8, CONTENT_TOP, Math.max(9, width - 8), viewportBottom);
        try {
            int y = CONTENT_TOP - scrollOffset;
            for (ContentLine line : content) {
                y += line.gapBefore();
                y = drawWrapped(graphics, line.text(), 16, y, Math.max(1, width - 32), line.color());
            }
            contentHeight = Math.max(0, y - (CONTENT_TOP - scrollOffset));
        } finally {
            graphics.disableScissor();
        }

        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            drawScrollbar(graphics, viewportBottom, maxScroll);
            graphics.drawCenteredString(font,
                    Component.translatable("caustiq.diagnostics.issue_details.scroll_hint"),
                    width / 2, height - 40, 0xFF999999);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        if (mouseY < CONTENT_TOP || mouseY >= viewportBottom() || deltaY == 0) {
            return super.mouseScrolled(mouseX, mouseY, deltaY);
        }
        int previous = scrollOffset;
        int movement = (int) Math.round(deltaY * 18.0);
        if (movement == 0) movement = deltaY > 0 ? 1 : -1;
        scrollOffset = Math.clamp(scrollOffset - movement, 0, maxScroll());
        return scrollOffset != previous || super.mouseScrolled(mouseX, mouseY, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int maxScroll = maxScroll();
        if (maxScroll == 0) return super.keyPressed(keyCode, scanCode, modifiers);
        int pageStep = Math.max(18, viewportBottom() - CONTENT_TOP - 18);
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> scrollOffset = Math.max(0, scrollOffset - 18);
            case GLFW.GLFW_KEY_DOWN -> scrollOffset = Math.min(maxScroll, scrollOffset + 18);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollOffset = Math.max(0, scrollOffset - pageStep);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollOffset = Math.min(maxScroll, scrollOffset + pageStep);
            case GLFW.GLFW_KEY_HOME -> scrollOffset = 0;
            case GLFW.GLFW_KEY_END -> scrollOffset = maxScroll;
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private List<ContentLine> buildContent() {
        List<ContentLine> lines = new ArrayList<>();
        lines.add(new ContentLine(Component.translatable(
                "caustiq.diagnostics.issue_details.candidate", issue.candidateName()), 0xFFFFFFFF, 0));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.diagnostics.issue_details.category", issueType()), 0xFFAAAAAA, 4));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.diagnostics.issue_details.reason_heading"), 0xFFFFFFFF, 12));
        lines.add(new ContentLine(Component.literal(issue.reason()), 0xFFCCCCCC, 2));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.diagnostics.issue_details.guidance_heading"), 0xFFFFFFFF, 12));
        lines.add(new ContentLine(issueGuidance(), 0xFF88CCFF, 2));
        lines.add(new ContentLine(Component.translatable(
                "caustiq.diagnostics.issue_details.privacy"), 0xFF888888, 12));
        return List.copyOf(lines);
    }

    private Component issueType() {
        return Component.translatable("caustiq.diagnostics.issue_type."
                + issue.kind().name().toLowerCase(Locale.ROOT));
    }

    private Component issueGuidance() {
        return Component.translatable("caustiq.diagnostics.issue_guidance."
                + issue.kind().name().toLowerCase(Locale.ROOT));
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

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewportBottom() - CONTENT_TOP));
    }

    private void drawScrollbar(GuiGraphics graphics, int viewportBottom, int maxScroll) {
        int trackHeight = viewportBottom - CONTENT_TOP;
        int thumbHeight = Math.min(trackHeight,
                Math.max(14, trackHeight * trackHeight / Math.max(trackHeight, contentHeight)));
        int thumbTravel = Math.max(0, trackHeight - thumbHeight);
        int thumbY = CONTENT_TOP + (int) ((long) thumbTravel * scrollOffset / maxScroll);
        graphics.fill(width - 5, CONTENT_TOP, width - 3, viewportBottom, 0x66555555);
        graphics.fill(width - 5, thumbY, width - 3, thumbY + thumbHeight, 0xFF88CCFF);
    }

    private record ContentLine(Component text, int color, int gapBefore) { }
}
