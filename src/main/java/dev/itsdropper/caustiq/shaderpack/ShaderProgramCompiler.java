package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Preprocesses pack fragment stages and compiles them through Minecraft's GPU pipeline system.
 * Minecraft owns backend compilation and state; this avoids bypassing its 1.21.11 GPU abstraction.
 */
public final class ShaderProgramCompiler {
    private static final String FULLSCREEN_VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            out vec2 texcoord;
            out vec2 vTexCoord;
            out vec2 uv;
            void main() {
                const vec2 positions[3] = vec2[3](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                const vec2 coordinates[3] = vec2[3](
                    vec2(0.0, 0.0),
                    vec2(2.0, 0.0),
                    vec2(0.0, 2.0)
                );
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
                texCoord = coordinates[gl_VertexID];
                texcoord = coordinates[gl_VertexID];
                vTexCoord = coordinates[gl_VertexID];
                uv = coordinates[gl_VertexID];
            }
            """;

    public CompiledShaderProgram compile(
            ShaderPackProgramCatalog.Program program,
            GlslPreprocessor.SourceProvider sourceProvider
    ) throws IOException {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sourceProvider, "sourceProvider");

        // The first executable pass uses the pack's fragment stage with a stable fullscreen
        // triangle vertex stage. The pack's own vertex stage remains catalogued for the
        // compatibility layer, but is not assumed to match Minecraft's vertex contract.
        GlslPreprocessor.Result fragment = new GlslPreprocessor()
                .preprocess(program.fragmentSource(), sourceProvider);

        String key = program.name().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_./-]", "_");
        Identifier pipelineId = Identifier.fromNamespaceAndPath("caustiq", "dynamic/" + key);
        Identifier vertexId = Identifier.fromNamespaceAndPath("caustiq", "dynamic/" + key + ".vsh");
        Identifier fragmentId = Identifier.fromNamespaceAndPath("caustiq", "dynamic/" + key + ".fsh");

        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(pipelineId)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withSampler("DiffuseSampler")
                .withSampler("colortex0")
                .withSampler("gcolor")
                .withSampler("texture")
                .withSampler("tex")
                .build();

        ShaderSource shaderSource = (id, type) -> {
            if (vertexId.equals(id)) return FULLSCREEN_VERTEX_SHADER;
            if (fragmentId.equals(id)) return fragment.source();
            return null;
        };
        try {
            RenderSystem.getDevice().precompilePipeline(pipeline, shaderSource);
        } catch (RuntimeException exception) {
            throw new IOException(program.name() + ": Minecraft GPU pipeline compilation failed: "
                    + exception.getMessage(), exception);
        }
        return new CompiledShaderProgram(program.name(), pipeline);
    }
}
