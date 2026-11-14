package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import java.util.OptionalInt;

/**
 * Executes the final color pass through Minecraft's GPU command encoder.
 * Source and destination are texture views, not OpenGL framebuffer IDs.
 */
public final class ShaderPostProcessPass implements AutoCloseable {
    private TextureTarget outputTarget;
    private int width;
    private int height;

    public void render(RenderTarget mainTarget, CompiledShaderProgram program) {
        if (mainTarget == null || program == null) return;
        int targetWidth = mainTarget.width;
        int targetHeight = mainTarget.height;
        if (targetWidth <= 0 || targetHeight <= 0) return;

        ensureTarget(targetWidth, targetHeight);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        try (RenderPass pass = encoder.createRenderPass(
                () -> "Caustiq final pass",
                outputTarget.getColorTextureView(),
                OptionalInt.empty()
        )) {
            pass.setPipeline(program.pipeline());
            pass.bindTexture("DiffuseSampler", mainTarget.getColorTextureView(), sampler);
            pass.bindTexture("colortex0", mainTarget.getColorTextureView(), sampler);
            pass.bindTexture("colortex1", textureOrMain(NvidiumGBufferTargets.NORMAL, mainTarget), sampler);
            pass.bindTexture("colortex2", textureOrMain(NvidiumGBufferTargets.POSITION, mainTarget), sampler);
            pass.bindTexture("colortex3", textureOrMain(NvidiumGBufferTargets.MATERIAL, mainTarget), sampler);
            pass.bindTexture("colortex4", textureOrMain(NvidiumGBufferTargets.LIGHTMAP, mainTarget), sampler);
            pass.bindTexture("colortex5", textureOrMain(5, mainTarget), sampler);
            pass.bindTexture("colortex6", textureOrMain(6, mainTarget), sampler);
            pass.bindTexture("colortex7", textureOrMain(7, mainTarget), sampler);
            pass.bindTexture("gcolor", mainTarget.getColorTextureView(), sampler);
            pass.bindTexture("texture", mainTarget.getColorTextureView(), sampler);
            pass.bindTexture("tex", mainTarget.getColorTextureView(), sampler);
            pass.draw(0, 3);
        }

        // Copy only after the render pass has ended; source and destination are distinct textures.
        encoder.copyTextureToTexture(
                outputTarget.getColorTexture(),
                mainTarget.getColorTexture(),
                0, 0, 0, 0, 0,
                targetWidth, targetHeight
        );
    }

    private static com.mojang.blaze3d.textures.GpuTextureView textureOrMain(
            int attachment, RenderTarget mainTarget) {
        com.mojang.blaze3d.textures.GpuTextureView view = NvidiumGBufferTargets.view(attachment);
        return view == null ? mainTarget.getColorTextureView() : view;
    }

    private void ensureTarget(int targetWidth, int targetHeight) {
        if (outputTarget == null) {
            outputTarget = new TextureTarget("Caustiq final pass", targetWidth, targetHeight, false);
            width = targetWidth;
            height = targetHeight;
            return;
        }
        if (width != targetWidth || height != targetHeight) {
            outputTarget.resize(targetWidth, targetHeight);
            width = targetWidth;
            height = targetHeight;
        }
    }

    @Override
    public void close() {
        if (outputTarget != null) {
            outputTarget.destroyBuffers();
            outputTarget = null;
        }
        width = 0;
        height = 0;
    }
}
