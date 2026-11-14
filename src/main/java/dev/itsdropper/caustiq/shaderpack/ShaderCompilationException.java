package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.List;

/** A GLSL driver failure with raw output and source-mapped diagnostics retained. */
public final class ShaderCompilationException extends IOException {
    private final String sourcePath;
    private final String compilerLog;
    private final List<ShaderDiagnostic> diagnostics;

    public ShaderCompilationException(
            String programName,
            String sourcePath,
            String compilerLog,
            List<ShaderDiagnostic> diagnostics
    ) {
        super("GLSL compilation failed for program '" + programName + "' at " + sourcePath + ":\n"
                + (compilerLog == null || compilerLog.isBlank() ? "The driver supplied no diagnostic." : compilerLog));
        this.sourcePath = sourcePath;
        this.compilerLog = compilerLog == null ? "" : compilerLog;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String sourcePath() { return sourcePath; }
    public String compilerLog() { return compilerLog; }
    public List<ShaderDiagnostic> diagnostics() { return diagnostics; }
}
