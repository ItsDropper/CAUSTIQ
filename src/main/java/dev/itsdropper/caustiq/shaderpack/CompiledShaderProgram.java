package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL20C;

/** Owns a linked OpenGL program; close it on the render thread. */
public final class CompiledShaderProgram implements AutoCloseable {
    private final String name;
    private int programId;

    CompiledShaderProgram(String name, int programId) {
        this.name = name;
        this.programId = programId;
    }
    public String name() { return name; }
    public int programId() {
        if (programId == 0) throw new IllegalStateException("Shader program is closed: " + name);
        return programId;
    }
    @Override public void close() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
