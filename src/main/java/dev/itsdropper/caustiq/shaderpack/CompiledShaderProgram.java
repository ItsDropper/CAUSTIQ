package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns a linked OpenGL program; all operations must run on the render thread. */
public final class CompiledShaderProgram implements AutoCloseable {
    private final String name;
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private int programId;

    CompiledShaderProgram(String name, int programId) {
        this.name = Objects.requireNonNull(name, "name");
        if (programId <= 0) throw new IllegalArgumentException("Program ID must be positive");
        this.programId = programId;
    }

    public String name() { return name; }

    public int programId() {
        if (programId == 0) throw new IllegalStateException("Shader program is closed: " + name);
        return programId;
    }

    /** Makes this program current. */
    public void bind() {
        GL20C.glUseProgram(programId());
    }

    /** Clears the current program binding. */
    public void unbind() {
        ensureOpen();
        GL20C.glUseProgram(0);
    }

    /**
     * Sets an integer uniform (including sampler uniforms).
     * The program must be bound first. Returns false when the uniform is absent or optimized out.
     */
    public boolean setInt(String uniform, int value) {
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        GL20C.glUniform1i(location, value);
        return true;
    }

    public boolean setSampler(String uniform, int textureUnit) {
        return setInt(uniform, textureUnit);
    }

    public boolean setFloat(String uniform, float value) {
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        GL20C.glUniform1f(location, value);
        return true;
    }

    public boolean setVec2(String uniform, float x, float y) {
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        GL20C.glUniform2f(location, x, y);
        return true;
    }

    public boolean setVec3(String uniform, float x, float y, float z) {
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        GL20C.glUniform3f(location, x, y, z);
        return true;
    }

    public boolean setVec4(String uniform, float x, float y, float z, float w) {
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        GL20C.glUniform4f(location, x, y, z, w);
        return true;
    }

    /** Uploads one column-major 4x4 matrix without changing the caller's buffer position. */
    public boolean setMatrix4(String uniform, FloatBuffer matrix) {
        Objects.requireNonNull(matrix, "matrix");
        if (matrix.remaining() < 16) {
            throw new IllegalArgumentException("A 4x4 matrix requires at least 16 remaining floats");
        }
        int location = uniformLocation(uniform);
        if (location < 0) return false;
        FloatBuffer values = matrix.duplicate();
        values.limit(values.position() + 16);
        GL20C.glUniformMatrix4fv(location, false, values);
        return true;
    }

    private int uniformLocation(String uniform) {
        ensureOpen();
        Objects.requireNonNull(uniform, "uniform");
        if (uniform.isBlank()) throw new IllegalArgumentException("Uniform name must not be blank");
        return uniformLocations.computeIfAbsent(uniform,
                key -> GL20C.glGetUniformLocation(programId, key));
    }

    private void ensureOpen() {
        if (programId == 0) throw new IllegalStateException("Shader program is closed: " + name);
    }

    @Override
    public void close() {
        if (programId == 0) return;
        int activeProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (activeProgram == programId) GL20C.glUseProgram(0);
        GL20C.glDeleteProgram(programId);
        programId = 0;
        uniformLocations.clear();
    }
}
