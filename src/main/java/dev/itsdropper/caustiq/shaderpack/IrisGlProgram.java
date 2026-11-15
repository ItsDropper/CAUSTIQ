package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL20C;

import java.util.Objects;
import java.util.Set;

/** Linked OpenGL program for an Iris full-screen program pass. */
public final class IrisGlProgram implements AutoCloseable {
    private final String name;
    private final int programId;
    private final Set<String> samplers;
    private final Set<String> images;
    private final boolean compute;
    private final int localSizeX, localSizeY, localSizeZ;
    private final boolean usesGlobalY, usesGlobalZ;
    private boolean closed;

    IrisGlProgram(String name, int programId, Set<String> samplers, Set<String> images,
                  boolean compute, int localSizeX, int localSizeY, int localSizeZ,
                  boolean usesGlobalY, boolean usesGlobalZ) {
        this.name = Objects.requireNonNull(name, "name");
        this.programId = programId;
        this.samplers = Set.copyOf(samplers);
        this.images = Set.copyOf(images);
        this.compute = compute;
        this.localSizeX = localSizeX;
        this.localSizeY = localSizeY;
        this.localSizeZ = localSizeZ;
        this.usesGlobalY = usesGlobalY;
        this.usesGlobalZ = usesGlobalZ;
    }

    public String name() { return name; }
    public int programId() {
        if (closed) throw new IllegalStateException("Iris program is closed: " + name);
        return programId;
    }
    public Set<String> samplers() { return samplers; }
    public Set<String> images() { return images; }
    public boolean compute() { return compute; }
    public int localSizeX() { return localSizeX; }
    public int localSizeY() { return localSizeY; }
    public int localSizeZ() { return localSizeZ; }
    public boolean usesGlobalY() { return usesGlobalY; }
    public boolean usesGlobalZ() { return usesGlobalZ; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GL20C.glDeleteProgram(programId);
    }
}
