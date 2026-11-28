package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanProgress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Shared, path-safe presentation of live shader-pack discovery progress. */
final class ShaderPackScanProgressView {
    private static final int BAR_HEIGHT = 4;

    private ShaderPackScanProgressView() { }

    static Component message(ShaderPackRefreshStatus status, boolean includeCandidateName) {
        if (status == null) return null;
        if (status.isCancelling()) return Component.translatable("caustiq.scan.cancelling");
        if (!status.isScanning()) return null;
        ShaderPackScanProgress progress = status.progress();
        if (progress.stage() == ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES) {
            return Component.translatable("caustiq.scan.discovering");
        }
        if (progress.currentCandidate().isBlank()) {
            return Component.translatable("caustiq.scan.inspected_count",
                    progress.completedCandidates(), progress.totalCandidates());
        }
        if (includeCandidateName) {
            return Component.translatable("caustiq.scan.inspecting_candidate",
                    progress.completedCandidates() + 1, progress.totalCandidates(),
                    Component.literal(progress.currentCandidate()));
        }
        return Component.translatable("caustiq.scan.inspecting_count",
                progress.completedCandidates() + 1, progress.totalCandidates());
    }

    static void updateButton(
            Button button,
            ShaderPackRefreshStatus status,
            String idleTranslation,
            String retryTranslation
    ) {
        if (button == null || status == null) return;
        button.active = status.state() != ShaderPackRefreshStatus.State.STARTUP_SCANNING
                && !status.isCancelling();
        String translation = switch (status.state()) {
            case STARTUP_SCANNING -> "caustiq.scan.startup";
            case SCANNING -> "caustiq.scan.cancel";
            case CANCELLING -> "caustiq.scan.cancelling";
            case FAILED -> retryTranslation;
            default -> idleTranslation;
        };
        button.setMessage(Component.translatable(translation));
    }

    static void drawBar(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            ShaderPackRefreshStatus status
    ) {
        if (status == null || !status.isScanning() || width <= 0) return;
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, 0xFF333333);

        if (status.isDeterminate()) {
            int filled = (int) Math.round(width * status.progressFraction());
            if (filled > 0) {
                graphics.fill(x, y, x + filled, y + BAR_HEIGHT, 0xFF88CCFF);
            }
            return;
        }

        int segmentWidth = Math.max(18, width / 4);
        long cycle = Math.floorMod(System.nanoTime() / 8_000_000L, width + segmentWidth);
        int segmentLeft = x + (int) cycle - segmentWidth;
        int visibleLeft = Math.max(x, segmentLeft);
        int visibleRight = Math.min(x + width, segmentLeft + segmentWidth);
        if (visibleRight > visibleLeft) {
            graphics.fill(visibleLeft, y, visibleRight, y + BAR_HEIGHT, 0xFF88CCFF);
        }
    }
}
