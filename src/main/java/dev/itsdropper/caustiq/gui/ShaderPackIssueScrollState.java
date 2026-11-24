package dev.itsdropper.caustiq.gui;

import org.lwjgl.glfw.GLFW;

/** Bounded, testable scroll state shared by the issue-details viewport and its scrollbar. */
final class ShaderPackIssueScrollState {
    private int contentHeight;
    private int viewportHeight;
    private int offset;

    int contentHeight() {
        return contentHeight;
    }

    int viewportHeight() {
        return viewportHeight;
    }

    int offset() {
        return offset;
    }

    int maxOffset() {
        return Math.max(0, contentHeight - viewportHeight);
    }

    void setDimensions(int contentHeight, int viewportHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        this.viewportHeight = Math.max(0, viewportHeight);
        scrollTo(offset);
    }

    boolean scrollTo(long requestedOffset) {
        int next = (int) Math.max(0L, Math.min((long) maxOffset(), requestedOffset));
        if (next == offset) return false;
        offset = next;
        return true;
    }

    boolean scrollBy(long delta) {
        return scrollTo((long) offset + delta);
    }

    boolean scrollWheel(double verticalAmount, int pixelsPerWheelUnit) {
        if (!Double.isFinite(verticalAmount) || verticalAmount == 0 || pixelsPerWheelUnit <= 0) {
            return false;
        }

        double scaled = -verticalAmount * pixelsPerWheelUnit;
        long movement;
        if (scaled >= Long.MAX_VALUE) {
            movement = Long.MAX_VALUE;
        } else if (scaled <= Long.MIN_VALUE) {
            movement = Long.MIN_VALUE;
        } else {
            movement = Math.round(scaled);
        }
        if (movement == 0) movement = verticalAmount > 0 ? -1 : 1;
        return scrollBy(movement);
    }

    boolean handleKey(int keyCode, int lineStep, int pageStep) {
        if (maxOffset() == 0) return false;
        int line = Math.max(1, lineStep);
        int page = Math.max(line, pageStep);
        return switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> scrollBy(-line);
            case GLFW.GLFW_KEY_DOWN -> scrollBy(line);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollBy(-page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollBy(page);
            case GLFW.GLFW_KEY_HOME -> scrollTo(0);
            case GLFW.GLFW_KEY_END -> scrollTo(maxOffset());
            default -> false;
        };
    }

    int thumbHeight(int trackHeight, int minimumThumbHeight) {
        if (trackHeight <= 0) return 0;
        if (maxOffset() == 0) return trackHeight;

        int minimum = Math.max(1, Math.min(trackHeight, minimumThumbHeight));
        long proportional = (long) trackHeight * viewportHeight / Math.max(1, contentHeight);
        return (int) Math.min(trackHeight, Math.max((long) minimum, proportional));
    }

    int thumbOffset(int trackHeight, int thumbHeight) {
        if (trackHeight <= 0) return 0;
        int clampedThumbHeight = Math.max(0, Math.min(trackHeight, thumbHeight));
        int travel = trackHeight - clampedThumbHeight;
        int maximum = maxOffset();
        return maximum == 0 ? 0 : (int) ((long) travel * offset / maximum);
    }
}
