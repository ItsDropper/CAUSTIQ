package dev.itsdropper.caustiq.gui;

import dev.itsdropper.caustiq.shaderpack.ShaderPackOptionRange;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Discrete shader-pack option slider. Mouse movement updates the preview;
 * persistence and shader reload are committed once when the drag ends.
 */
final class ShaderPackOptionSlider extends AbstractSliderButton {
    private final String label;
    private final List<String> values;
    private final IntConsumer onCommit;
    private int committedIndex;

    ShaderPackOptionSlider(
            int x,
            int y,
            int width,
            int height,
            String label,
            List<String> values,
            int initialIndex,
            IntConsumer onCommit
    ) {
        super(x, y, width, height, Component.empty(), initialProgress(values, initialIndex));
        this.label = Objects.requireNonNull(label, "label");
        this.values = List.copyOf(values);
        this.onCommit = Objects.requireNonNull(onCommit, "onCommit");
        this.committedIndex = ShaderPackOptionRange.indexForProgress(value, this.values.size());
        updateMessage();
    }

    private static double initialProgress(List<String> values, int initialIndex) {
        Objects.requireNonNull(values, "values");
        if (values.size() < 2) {
            throw new IllegalArgumentException("A slider requires at least two allowed values");
        }
        return ShaderPackOptionRange.progressForIndex(initialIndex, values.size());
    }

    @Override
    protected void updateMessage() {
        // AbstractSliderButton may invoke this during its constructor, before our fields are assigned.
        if (values == null || label == null) return;
        int index = ShaderPackOptionRange.indexForProgress(value, values.size());
        setMessage(Component.literal(label + ": " + values.get(index)));
    }

    @Override
    protected void applyValue() {
        // Do not persist or reload while the pointer is moving. The visible message is the preview.
        updateMessage();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        commitSelection();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int current = currentIndex();
        int target = switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN -> current - 1;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP -> current + 1;
            case GLFW.GLFW_KEY_HOME -> 0;
            case GLFW.GLFW_KEY_END -> values.size() - 1;
            default -> -1;
        };
        if (target >= 0) {
            setValue(ShaderPackOptionRange.progressForIndex(
                    Math.clamp(target, 0, values.size() - 1), values.size()));
            commitSelection();
            return true;
        }

        boolean handled = super.keyPressed(event);
        if (handled) commitSelection();
        return handled;
    }

    private int currentIndex() {
        return ShaderPackOptionRange.indexForProgress(value, values.size());
    }

    private void commitSelection() {
        int selected = currentIndex();
        if (selected == committedIndex) return;
        committedIndex = selected;
        onCommit.accept(selected);
    }
}
