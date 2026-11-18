package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackInspection;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Asynchronous, path-safe file inventory and metadata view for one discovered shader pack. */
public final class ShaderPackInspectorScreen extends Screen {
    private static final int FILE_ROW_HEIGHT = 12;
    private static final int MAX_PAGE_SIZE = 14;

    private final Screen parent;
    private final ShaderPackDescriptor pack;
    private ShaderPackInspection inspection;
    private boolean loading;
    private String failureMessage = "";
    private int page;

    public ShaderPackInspectorScreen(Screen parent, ShaderPackDescriptor pack) {
        super(Component.translatable("caustiq.inspector.title"));
        this.parent = parent;
        this.pack = pack;
    }

    @Override
    protected void init() {
        clearWidgets();
        if (inspection == null && !loading && failureMessage.isEmpty()) {
            startInspection();
        }

        int left = width / 2 - 155;
        int footerY = height - 56;
        if (inspection != null) {
            int pages = pageCount();
            page = Math.clamp(page, 0, pages - 1);
            Button previous = Button.builder(Component.translatable("caustiq.inspector.previous"),
                    button -> changePage(-1)).bounds(left, footerY, 72, 20).build();
            Button next = Button.builder(Component.translatable("caustiq.inspector.next"),
                    button -> changePage(1)).bounds(left + 76, footerY, 72, 20).build();
            previous.active = page > 0;
            next.active = page + 1 < pages;
            addRenderableWidget(previous);
            addRenderableWidget(next);
            addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.copy"), button -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(inspection.createReport());
                button.setMessage(Component.translatable("caustiq.inspector.copied"));
            }).bounds(left + 152, footerY, 158, 20).build());
        } else if (!loading) {
            addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.retry"),
                    button -> {
                        failureMessage = "";
                        startInspection();
                        init();
                    }).bounds(width / 2 - 50, footerY, 100, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void startInspection() {
        loading = true;
        failureMessage = "";
        CompletableFuture.supplyAsync(() -> {
            try {
                return ShaderPackInspection.inspect(pack);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (failure != null) {
                failureMessage = summarizeFailure(failure);
            } else {
                inspection = result;
                page = 0;
            }
            if (Minecraft.getInstance().screen == this) {
                init();
            }
        }));
    }

    private static String summarizeFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return ShaderPackIssue.sanitizeDisplayText(
                message == null || message.isBlank() ? cause.getClass().getSimpleName() : message);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFFFF);

        if (loading) {
            drawWrapped(graphics, Component.translatable("caustiq.inspector.loading"),
                    16, 52, width - 32, 0xFFFFAA00);
            return;
        }
        if (!failureMessage.isBlank()) {
            drawWrapped(graphics, Component.translatable("caustiq.inspector.load_failed", failureMessage),
                    16, 52, width - 32, 0xFFFF7777);
            return;
        }
        if (inspection == null) return;

        ShaderPackDescriptor descriptor = inspection.pack();
        int y = 42;
        y = drawWrapped(graphics, Component.literal(ShaderPackIssue.sanitizeDisplayText(descriptor.displayName())),
                16, y, width - 32, 0xFFFFFFFF);
        Component sourceType = Component.translatable(descriptor.sourceType() == ShaderPackSourceType.DIRECTORY
                ? "caustiq.settings.pack_source_directory" : "caustiq.settings.pack_source_archive");
        String sourceName = descriptor.source().getFileName() == null
                ? "unknown" : ShaderPackIssue.sanitizeDisplayText(descriptor.source().getFileName().toString());
        y = drawWrapped(graphics, Component.translatable("caustiq.inspector.source",
                sourceType, Component.literal(sourceName)), 16, y, width - 32, 0xFFCCCCCC);
        y = drawWrapped(graphics, Component.translatable("caustiq.inspector.layout",
                Component.translatable("caustiq.inspector.layout_optifine")),
                16, y, width - 32, 0xFFAAAAAA);
        y = drawWrapped(graphics, Component.translatable("caustiq.inspector.summary",
                descriptor.shaderFileCount(), descriptor.pipelineEntryPointCount(),
                formatSize(descriptor.declaredUncompressedBytes())),
                16, y, width - 32, 0xFFAAAAAA);
        Component metadata = inspection.metadataOptionCount() >= 0
                ? Component.translatable("caustiq.inspector.metadata_options", inspection.metadataOptionCount())
                : Component.translatable("caustiq.inspector.metadata_unavailable", inspection.metadataFailure());
        y = drawWrapped(graphics, metadata, 16, y, width - 32,
                inspection.metadataOptionCount() >= 0 ? 0xFFAAAAAA : 0xFFFFAA00);
        y = drawWrapped(graphics, Component.translatable("caustiq.inspector.file_count",
                inspection.files().size()), 16, y + 2, width - 32, 0xFFCCCCCC);
        y = drawWrapped(graphics, Component.translatable("caustiq.inspector.file_heading"),
                16, y + 2, width - 32, 0xFFFFFFFF);

        if (inspection.files().isEmpty()) {
            drawWrapped(graphics, Component.translatable("caustiq.inspector.empty"),
                    16, y + 8, width - 32, 0xFFAAAAAA);
            return;
        }

        int first = page * pageSize();
        int last = Math.min(first + pageSize(), inspection.files().size());
        for (int index = first; index < last; index++) {
            String path = "shaders/" + inspection.files().get(index);
            List<FormattedCharSequence> lines = font.split(Component.literal(path), width - 32);
            int rowY = y + 8 + (index - first) * FILE_ROW_HEIGHT;
            if (!lines.isEmpty()) {
                graphics.drawString(font, lines.getFirst(), 16, rowY, 0xFFBBBBBB);
            }
            if (lines.size() > 1) {
                graphics.drawString(font, Component.literal("…"),
                        width - 16 - font.width("…"), rowY, 0xFF888888);
            }
        }
        graphics.drawCenteredString(font, Component.translatable("caustiq.inspector.page",
                page + 1, pageCount()), width / 2, height - 70, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private int pageSize() {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, (height - 178) / FILE_ROW_HEIGHT));
    }

    private int pageCount() {
        int fileCount = inspection == null ? 0 : inspection.files().size();
        return Math.max(1, (fileCount + pageSize() - 1) / pageSize());
    }

    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pageCount() - 1);
        init();
    }

    private int drawWrapped(
            GuiGraphics graphics,
            Component component,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        for (FormattedCharSequence line : font.split(component, maxWidth)) {
            graphics.drawCenteredString(font, line, width / 2, y, color);
            y += 10;
        }
        return y;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
