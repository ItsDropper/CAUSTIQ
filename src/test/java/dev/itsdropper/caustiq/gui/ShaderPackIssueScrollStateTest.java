package dev.itsdropper.caustiq.gui;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackIssueScrollStateTest {
    @Test
    void clampsOffsetsWhenContentOrViewportChanges() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(1_000, 200);

        assertEquals(800, state.maxOffset());
        assertTrue(state.scrollTo(650));
        state.setDimensions(300, 200);
        assertEquals(100, state.offset());
        state.setDimensions(100, 200);
        assertEquals(0, state.offset());
        state.setDimensions(-10, -20);
        assertEquals(0, state.maxOffset());
    }

    @Test
    void wheelScrollingHandlesTinyDeltasAndBothDirections() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(1_000, 200);
        state.scrollTo(100);

        assertTrue(state.scrollWheel(1.0, 18));
        assertEquals(82, state.offset());
        assertTrue(state.scrollWheel(-0.01, 18));
        assertEquals(83, state.offset());
        assertFalse(state.scrollWheel(Double.NaN, 18));
        assertFalse(state.scrollWheel(Double.POSITIVE_INFINITY, 18));
        assertFalse(state.scrollWheel(1.0, 0));
    }

    @Test
    void keyboardScrollingSupportsLinesPagesAndAbsolutePositions() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(1_000, 200);

        assertTrue(state.handleKey(GLFW.GLFW_KEY_DOWN, 18, 80));
        assertEquals(18, state.offset());
        assertTrue(state.handleKey(GLFW.GLFW_KEY_PAGE_DOWN, 18, 80));
        assertEquals(98, state.offset());
        assertTrue(state.handleKey(GLFW.GLFW_KEY_HOME, 18, 80));
        assertEquals(0, state.offset());
        assertTrue(state.handleKey(GLFW.GLFW_KEY_END, 18, 80));
        assertEquals(800, state.offset());
        assertTrue(state.handleKey(GLFW.GLFW_KEY_UP, 18, 80));
        assertEquals(782, state.offset());
        assertTrue(state.handleKey(GLFW.GLFW_KEY_PAGE_UP, 18, 80));
        assertEquals(702, state.offset());
        assertFalse(state.handleKey(GLFW.GLFW_KEY_LEFT, 18, 80));
    }

    @Test
    void keyboardScrollingDoesNotConsumeKeysWhenThereIsNoOverflow() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(100, 200);

        assertFalse(state.handleKey(GLFW.GLFW_KEY_DOWN, 18, 80));
        assertFalse(state.handleKey(GLFW.GLFW_KEY_END, 18, 80));
    }

    @Test
    void scrollbarThumbTracksTheViewportAndScrollPosition() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(1_000, 200);

        assertEquals(20, state.thumbHeight(100, 14));
        assertEquals(0, state.thumbOffset(100, 20));
        state.scrollTo(400);
        assertEquals(40, state.thumbOffset(100, 20));
        state.scrollTo(800);
        assertEquals(80, state.thumbOffset(100, 20));

        state.setDimensions(100, 200);
        assertEquals(100, state.thumbHeight(100, 14));
        assertEquals(0, state.thumbOffset(100, 100));
        assertEquals(0, state.thumbHeight(0, 14));
    }

    @Test
    void saturatesLargeScrollDeltasWithoutIntegerOverflow() {
        ShaderPackIssueScrollState state = new ShaderPackIssueScrollState();
        state.setDimensions(Integer.MAX_VALUE, 0);

        assertTrue(state.scrollBy(Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, state.offset());
        assertTrue(state.scrollBy(Long.MIN_VALUE));
        assertEquals(0, state.offset());
    }
}
