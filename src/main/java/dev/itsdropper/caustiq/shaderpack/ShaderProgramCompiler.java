package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL20C;
import java.io.IOException;
import java.util.Objects;

/** Compiles and links GLSL stages on Minecraft's current OpenGL render context. */
public final class ShaderProgramCompiler {
    private static final int MAX_SHADER_LOG_LENGTH = 32_768;

    public CompiledShaderProgram compile(ShaderPackProgramCatalog.Program program,
                                         GlslPreprocessor.SourceProvider sourceProvider) throws IOException {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        GlslPreprocessor preprocessor = new GlslPreprocessor();
        String vertexSource = preprocessor.preprocess(program.vertexSource(), sourceProvider).source();
        String fragmentSource = preprocessor.preprocess(program.fragmentSource(), sourceProvider).source();
        int vertexShader = 0, fragmentShader = 0, linkedProgram = 0;
        try {
            vertexShader = compileStage(GL20C.GL_VERTEX_SHADER, program.vertexSource(), vertexSource);
            fragmentShader = compileStage(GL20C.GL_FRAGMENT_SHADER, program.fragmentSource(), fragmentSource);
            linkedProgram = GL20C.glCreateProgram();
            if (linkedProgram == 0) throw new IOException(program.name() + ": OpenGL did not allocate a program object.");
            GL20C.glAttachShader(linkedProgram, vertexShader);
            GL20C.glAttachShader(linkedProgram, fragmentShader);
            GL20C.glLinkProgram(linkedProgram);
            if (GL20C.glGetProgrami(linkedProgram, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                throw new IOException(program.name() + ": program link failed:\n"
                        + GL20C.glGetProgramInfoLog(linkedProgram, MAX_SHADER_LOG_LENGTH));
            }
            CompiledShaderProgram result = new CompiledShaderProgram(program.name(), linkedProgram);
            linkedProgram = 0;
            return result;
        } finally {
            if (linkedProgram != 0) GL20C.glDeleteProgram(linkedProgram);
            if (vertexShader != 0) GL20C.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL20C.glDeleteShader(fragmentShader);
        }
    }

    private int compileStage(int type, String path, String source) throws IOException {
        int shader = GL20C.glCreateShader(type);
        if (shader == 0) throw new IOException(path + ": OpenGL did not allocate a shader object.");
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader, MAX_SHADER_LOG_LENGTH);
            GL20C.glDeleteShader(shader);
            throw new IOException(path + ": GLSL compilation failed:\n" + log);
        }
        return shader;
    }
}
