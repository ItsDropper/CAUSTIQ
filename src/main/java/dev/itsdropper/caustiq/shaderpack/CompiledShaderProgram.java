package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Objects;

/** A successfully precompiled Minecraft GPU render pipeline for a shader-pack pass. */
public final class CompiledShaderProgram implements AutoCloseable {
    private final String name;
    private final RenderPipeline pipeline;
    private boolean closed;

    CompiledShaderProgram(String name, RenderPipeline pipeline) {
        this.name = Objects.requireNonNull(name, "name");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public String name() { return name; }

    public RenderPipeline pipeline() {
        if (closed) throw new IllegalStateException("Shader program is closed: " + name);
        return pipeline;
    }

    /** Pipelines are owned by Minecraft's GPU device; closing this wrapper only invalidates it. */
    @Override
    public void close() { closed = true; }
}
