package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles pack fragment stages through Minecraft's GPU pipeline abstraction. */
public final class ShaderProgramCompiler {
    private static final Pattern DEFINE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern DEFINE_VALUE = Pattern.compile("[A-Za-z0-9_+./-]{1,128}");
    private static final Pattern DEFINE_LINE = Pattern.compile("^(\\s*#\\s*define\\s+)([A-Za-z_][A-Za-z0-9_]*)(?:\\s+.*)?$");
    private static final String FULLSCREEN_VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            out vec2 texcoord;
            out vec2 vTexCoord;
            out vec2 uv;
            void main() {
                const vec2 positions[3] = vec2[3](
                    vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
                );
                const vec2 coordinates[3] = vec2[3](
                    vec2(0.0, 0.0), vec2(2.0, 0.0), vec2(0.0, 2.0)
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
        return compile(program, sourceProvider, Map.of());
    }

    public CompiledShaderProgram compile(
            ShaderPackProgramCatalog.Program program,
            GlslPreprocessor.SourceProvider sourceProvider,
            Map<String, String> defines
    ) throws IOException {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        GlslPreprocessor.Result preprocessed = new GlslPreprocessor()
                .preprocess(program.fragmentSource(), sourceProvider);
        String fragmentSource = new GlslCompatibilityTranslator()
                .translateLegacyFragment(preprocessed.source());
        fragmentSource = applyDefines(fragmentSource, defines);

        String key = program.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
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
            if (fragmentId.equals(id)) return fragmentSource;
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

    private static String applyDefines(String source, Map<String, String> defines) throws IOException {
        if (defines == null || defines.isEmpty()) return source;
        Map<String, String> safe = new TreeMap<>();
        for (Map.Entry<String, String> entry : defines.entrySet()) {
            if (!DEFINE_NAME.matcher(entry.getKey()).matches()
                    || !DEFINE_VALUE.matcher(entry.getValue()).matches()) {
                throw new IOException("Unsafe shader option macro: " + entry.getKey());
            }
            safe.put(entry.getKey(), entry.getValue());
        }

        StringBuilder rewritten = new StringBuilder(source.length() + safe.size() * 32);
        java.util.Set<String> replaced = new java.util.HashSet<>();
        for (String line : source.split("\\R", -1)) {
            Matcher matcher = DEFINE_LINE.matcher(line);
            if (matcher.matches() && safe.containsKey(matcher.group(2))) {
                String name = matcher.group(2);
                rewritten.append(matcher.group(1)).append(name).append(' ').append(safe.get(name));
                replaced.add(name);
            } else {
                rewritten.append(line);
            }
            rewritten.append('\n');
        }

        StringBuilder missing = new StringBuilder();
        safe.forEach((name, value) -> {
            if (!replaced.contains(name)) missing.append("#define ").append(name).append(' ').append(value).append('\n');
        });
        String result = rewritten.toString();
        int versionEnd = result.startsWith("#version") ? result.indexOf('\n') : -1;
        if (versionEnd >= 0) return result.substring(0, versionEnd + 1) + missing + result.substring(versionEnd + 1);
        return missing + result;
    }
}
