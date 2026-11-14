package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL20C;

import java.util.Objects;
import java.util.Set;

/** Linked OpenGL program for an Iris full-screen program pass. */
public final class IrisGlProgram implements AutoCloseable {
    private final String name;
    private final int programId;
    private final Set<String> samplers;
    private boolean closed;

    IrisGlProgram(String name, int programId, Set<String> samplers) {
        this.name = Objects.requireNonNull(name, "name");
        this.programId = programId;
        this.samplers = Set.copyOf(samplers);
    }

    public String name() { return name; }
    public int programId() {
        if (closed) throw new IllegalStateException("Iris program is closed: " + name);
        return programId;
    }
    public Set<String> samplers() { return samplers; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GL20C.glDeleteProgram(programId);
    }
}
