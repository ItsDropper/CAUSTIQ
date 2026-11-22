package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackFileBrowserModel;
import dev.itsdropper.caustiq.shaderpack.ShaderPackInspection;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackSourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Searchable, filterable and path-safe file inventory for one discovered shader pack. */
public final class ShaderPackInspectorScreen extends Screen {
    private static final int FILE_ROW_HEIGHT = 12;
    private static final int MAX_PAGE_SIZE = 14;
    private static final int HEADER_TOP = 72;

    private final Screen parent;
    private final ShaderPackDescriptor pack;
    private ShaderPackInspection inspection;
    private ShaderPackFileBrowserModel fileBrowser;
    private List<String> filteredFiles = List.of();
    private boolean loading;
    private String failureMessage = "";
    private String query = "";
    private int page;
    private ShaderPackFileBrowserModel.FileFilter fileFilter =
            ShaderPackFileBrowserModel.FileFilter.ALL;
    private EditBox searchField;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button copyPathsButton;

    public ShaderPackInspectorScreen(Screen parent, ShaderPackDescriptor pack) {
        super(Component.translatable("caustiq.inspector.title"));
        this.parent = parent;
        this.pack = pack;
    }

    @Override
    protected void init() {
        clearWidgets();
        previousPageButton = null;
        nextPageButton = null;
        copyPathsButton = null;
        searchField = null;

        if (inspection == null && !loading && failureMessage.isEmpty()) {
            startInspection();
        }
        if (inspection != null && fileBrowser == null) {
            fileBrowser = new ShaderPackFileBrowserModel(inspection.files());
            filteredFiles = fileBrowser.apply(query, fileFilter);
        }

        int left = width / 2 - 155;
        if (inspection != null) {
            searchField = new EditBox(font, left, 42, 168, 20,
                    Component.translatable("caustiq.inspector.search_label"));
            searchField.setMaxLength(100);
            searchField.setHint(Component.translatable("caustiq.inspector.search_hint"));
            searchField.setValue(query);
            searchField.setResponder(value -> {
                query = value;
                page = 0;
                rebuildFilteredFiles();
            });
            addRenderableWidget(searchField);
            addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.clear"),
                    button -> {
                        searchField.setValue("");
                        setFocused(searchField);
                    }).bounds(left + 172, 42, 36, 20).build());
            addRenderableWidget(Button.builder(fileFilterLabel(), button -> {
                fileFilter = fileFilter.next();
                page = 0;
                button.setMessage(fileFilterLabel());
                rebuildFilteredFiles();
            }).bounds(left + 212, 42, 98, 20).build());

            previousPageButton = Button.builder(Component.translatable("caustiq.inspector.previous"),
                    button -> changePage(-1)).bounds(left, height - 56, 58, 20).build();
            nextPageButton = Button.builder(Component.translatable("caustiq.inspector.next"),
                    button -> changePage(1)).bounds(left + 62, height - 56, 58, 20).build();
            copyPathsButton = Button.builder(Component.translatable("caustiq.inspector.copy_paths"), button -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(
                        inspection.createFileListReport(filteredFiles));
                button.setMessage(Component.translatable("caustiq.inspector.paths_copied"));
            }).bounds(left + 124, height - 56, 82, 20).build();
            addRenderableWidget(previousPageButton);
            addRenderableWidget(nextPageButton);
            addRenderableWidget(copyPathsButton);
            addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.copy"), button -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(inspection.createReport());
                button.setMessage(Component.translatable("caustiq.inspector.copied"));
            }).bounds(left + 210, height - 56, 100, 20).build());
            updateNavigationButtons();
        } else if (!loading) {
            addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.retry"),
                    button -> {
                        failureMessage = "";
                        startInspection();
                        init();
                    }).bounds(width / 2 - 50, height - 56, 100, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("caustiq.inspector.back"),
                button -> Minecraft.getInstance().setScreen(parent))
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
        if (searchField != null) {
            setFocused(searchField);
        }
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
                fileBrowser = new ShaderPackFileBrowserModel(result.files());
                filteredFiles = fileBrowser.apply(query, fileFilter);
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

        int y = HEADER_TOP;
        List<Component> header = headerComponents();
        for (int index = 0; index < header.size(); index++) {
            int color = switch (index) {
                case 0 -> 0xFFFFFFFF;
                case 4 -> inspection.metadataOptionCount() >= 0 ? 0xFFAAAAAA : 0xFFFFAA00;
                case 6 -> 0xFFFFFFFF;
                default -> 0xFFCCCCCC;
            };
            y = drawWrapped(graphics, header.get(index), 16, y, width - 32, color);
        }

        if (filteredFiles.isEmpty()) {
            Component empty = inspection.files().isEmpty()
                    ? Component.translatable("caustiq.inspector.empty")
                    : Component.translatable("caustiq.inspector.no_matches");
            drawWrapped(graphics, empty, 16, y + 8, width - 32, 0xFFAAAAAA);
            return;
        }

        int pageSize = pageSize();
        int first = page * pageSize;
        int last = Math.min(first + pageSize, filteredFiles.size());
        for (int index = first; index < last; index++) {
            String path = "shaders/" + filteredFiles.get(index);
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
        int listBottom = y + 8 + (last - first) * FILE_ROW_HEIGHT;
        int pageLabelY = height - 70;
        if (pageLabelY >= listBottom + 4) {
            graphics.drawCenteredString(font, Component.translatable("caustiq.inspector.page",
                    page + 1, pageCount()), width / 2, pageLabelY, 0xFFAAAAAA);
        }
    }

    private List<Component> headerComponents() {
        ShaderPackDescriptor descriptor = inspection.pack();
        Component sourceType = Component.translatable(descriptor.sourceType() == ShaderPackSourceType.DIRECTORY
                ? "caustiq.settings.pack_source_directory" : "caustiq.settings.pack_source_archive");
        String sourceName = descriptor.source().getFileName() == null
                ? "unknown" : ShaderPackIssue.sanitizeDisplayText(descriptor.source().getFileName().toString());
        Component metadata = inspection.metadataOptionCount() >= 0
                ? Component.translatable("caustiq.inspector.metadata_options", inspection.metadataOptionCount())
                : Component.translatable("caustiq.inspector.metadata_unavailable", inspection.metadataFailure());
        return List.of(
                Component.literal(ShaderPackIssue.sanitizeDisplayText(descriptor.displayName())),
                Component.translatable("caustiq.inspector.source", sourceType, Component.literal(sourceName)),
                Component.translatable("caustiq.inspector.layout",
                        Component.translatable("caustiq.inspector.layout_optifine")),
                Component.translatable("caustiq.inspector.summary",
                        descriptor.shaderFileCount(), descriptor.pipelineEntryPointCount(),
                        formatSize(descriptor.declaredUncompressedBytes())),
                metadata,
                Component.translatable("caustiq.inspector.file_count_filtered",
                        filteredFiles.size(), inspection.files().size()),
                Component.translatable("caustiq.inspector.file_heading")
        );
    }

    private int pageSize() {
        int availableRows = (height - 80 - (inventoryTop() + 8)) / FILE_ROW_HEIGHT;
        return Math.max(1, Math.min(MAX_PAGE_SIZE, availableRows));
    }

    private int inventoryTop() {
        int y = HEADER_TOP;
        for (Component component : headerComponents()) {
            y += Math.max(1, font.split(component, Math.max(1, width - 32)).size()) * 10;
        }
        return y;
    }

    private int pageCount() {
        return Math.max(1, (filteredFiles.size() + pageSize() - 1) / pageSize());
    }

    private void rebuildFilteredFiles() {
        filteredFiles = fileBrowser == null ? List.of() : fileBrowser.apply(query, fileFilter);
        updateNavigationButtons();
    }

    private Component fileFilterLabel() {
        Component value = Component.translatable(switch (fileFilter) {
            case ALL -> "caustiq.inspector.filter_all";
            case SHADER_SOURCES -> "caustiq.inspector.filter_shaders";
            case PROPERTIES -> "caustiq.inspector.filter_properties";
            case ASSETS -> "caustiq.inspector.filter_assets";
        });
        return Component.translatable("caustiq.inspector.filter", value);
    }

    private void updateNavigationButtons() {
        int pages = pageCount();
        page = Math.clamp(page, 0, pages - 1);
        if (previousPageButton != null) previousPageButton.active = page > 0;
        if (nextPageButton != null) nextPageButton.active = page + 1 < pages;
        if (copyPathsButton != null) copyPathsButton.active = !filteredFiles.isEmpty();
    }

    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pageCount() - 1);
        updateNavigationButtons();
        Button target = direction < 0 ? previousPageButton : nextPageButton;
        if (target != null && target.active) {
            setFocused(target);
        } else if (searchField != null) {
            setFocused(searchField);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
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
