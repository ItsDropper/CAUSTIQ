package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Compiles and links GLSL stages on Minecraft's current OpenGL render context. */
public final class ShaderProgramCompiler {
    private static final int MAX_SHADER_LOG_LENGTH = 32_768;
    private static final Map<String, Integer> FULLSCREEN_ATTRIBUTES = Map.of("Position", 0, "UV0", 1);

    public CompiledShaderProgram compile(
            ShaderPackProgramCatalog.Program program,
            GlslPreprocessor.SourceProvider sourceProvider
    ) throws IOException {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        ensureRenderContext();

        GlslPreprocessor preprocessor = new GlslPreprocessor();
        GlslPreprocessor.Result vertex = preprocessor.preprocess(program.vertexSource(), sourceProvider);
        GlslPreprocessor.Result fragment = preprocessor.preprocess(program.fragmentSource(), sourceProvider);
        int vertexShader = 0;
        int fragmentShader = 0;
        int linkedProgram = 0;
        try {
            vertexShader = compileStage(program.name(), GL20C.GL_VERTEX_SHADER,
                    program.vertexSource(), vertex);
            fragmentShader = compileStage(program.name(), GL20C.GL_FRAGMENT_SHADER,
                    program.fragmentSource(), fragment);

            linkedProgram = GL20C.glCreateProgram();
            if (linkedProgram == 0) {
                throw new IOException(program.name() + ": OpenGL did not allocate a program object.");
            }
            GL20C.glAttachShader(linkedProgram, vertexShader);
            GL20C.glAttachShader(linkedProgram, fragmentShader);
            for (Map.Entry<String, Integer> attribute : FULLSCREEN_ATTRIBUTES.entrySet()) {
                GL20C.glBindAttribLocation(linkedProgram, attribute.getValue(), attribute.getKey());
            }
            GL20C.glLinkProgram(linkedProgram);
            if (GL20C.glGetProgrami(linkedProgram, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                throw new IOException(program.name() + ": program link failed:\n"
                        + GL20C.glGetProgramInfoLog(linkedProgram, MAX_SHADER_LOG_LENGTH));
            }

            GL20C.glDetachShader(linkedProgram, vertexShader);
            GL20C.glDetachShader(linkedProgram, fragmentShader);
            CompiledShaderProgram result = new CompiledShaderProgram(program.name(), linkedProgram);
            linkedProgram = 0;
            return result;
        } finally {
            if (linkedProgram != 0) GL20C.glDeleteProgram(linkedProgram);
            if (vertexShader != 0) GL20C.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL20C.glDeleteShader(fragmentShader);
        }
    }

    private int compileStage(
            String programName,
            int type,
            String sourcePath,
            GlslPreprocessor.Result source
    ) throws IOException {
        int shader = GL20C.glCreateShader(type);
        if (shader == 0) {
            throw new IOException(sourcePath + ": OpenGL did not allocate a shader object.");
        }
        GL20C.glShaderSource(shader, source.source());
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader, MAX_SHADER_LOG_LENGTH);
            GL20C.glDeleteShader(shader);
            throw new ShaderCompilationException(programName, sourcePath, log,
                    new ShaderDiagnosticParser().parse(log, source.sourceMap()));
        }
        return shader;
    }

    private static void ensureRenderContext() {
        try {
            GL.getCapabilities();
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(
                    "GLSL compilation must run on Minecraft's render thread with an OpenGL context current.",
                    exception
            );
        }
    }
}
