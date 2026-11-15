package dev.itsdropper.caustiq.shaderpack;

/** Pixel dimensions for Iris render targets derived from the main framebuffer. */
public record IrisRenderTargetSize(int width, int height) {
    public IrisRenderTargetSize {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Render-target dimensions must be positive.");
        }
    }

    public static IrisRenderTargetSize of(int baseWidth, int baseHeight, float scaleX, float scaleY) {
        if (baseWidth <= 0 || baseHeight <= 0
                || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || scaleX <= 0.0f || scaleY <= 0.0f) {
            throw new IllegalArgumentException("Invalid base dimensions or Iris render-target scale.");
        }
        long scaledWidth = Math.round((double) baseWidth * scaleX);
        long scaledHeight = Math.round((double) baseHeight * scaleY);
        if (scaledWidth > Integer.MAX_VALUE || scaledHeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Scaled Iris render target exceeds supported dimensions.");
        }
        return new IrisRenderTargetSize((int) Math.max(1L, scaledWidth), (int) Math.max(1L, scaledHeight));
    }
}
