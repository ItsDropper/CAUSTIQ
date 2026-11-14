package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShaderDiagnosticParserTest {
    private final ShaderDiagnosticParser parser = new ShaderDiagnosticParser();
    private final List<GlslPreprocessor.SourceLocation> sourceMap = List.of(
            new GlslPreprocessor.SourceLocation(0, "gbuffers_terrain.fsh"),
            new GlslPreprocessor.SourceLocation(1, "lib/lighting.glsl")
    );

    @Test
    void mapsNvidiaStyleSourceAndLineDiagnostics() {
        List<ShaderDiagnostic> diagnostics = parser.parse("1(27) : error C0000: syntax error, unexpected IDENTIFIER", sourceMap);
        assertEquals(1, diagnostics.size());
        assertEquals("lib/lighting.glsl", diagnostics.getFirst().sourcePath());
        assertEquals(27, diagnostics.getFirst().line());
        assertEquals(ShaderDiagnostic.Severity.ERROR, diagnostics.getFirst().severity());
    }

    @Test
    void mapsMesaStyleSourceLineAndColumnDiagnostics() {
        List<ShaderDiagnostic> diagnostics = parser.parse("ERROR: 0:14(8): error: undeclared identifier", sourceMap);
        assertEquals(1, diagnostics.size());
        assertEquals("gbuffers_terrain.fsh", diagnostics.getFirst().sourcePath());
        assertEquals(14, diagnostics.getFirst().line());
        assertEquals(8, diagnostics.getFirst().column());
    }

    @Test
    void acceptsMesaLogsWithoutAnExplicitSeverityToken() {
        List<ShaderDiagnostic> diagnostics = parser.parse("ERROR: 1:4(2): undeclared identifier", sourceMap);
        assertEquals(1, diagnostics.size());
        assertEquals(ShaderDiagnostic.Severity.ERROR, diagnostics.getFirst().severity());
        assertEquals("lib/lighting.glsl", diagnostics.getFirst().sourcePath());
    }

    @Test
    void keepsUnknownSourceIdsWithoutInventingPaths() {
        List<ShaderDiagnostic> diagnostics = parser.parse("9:9: error: invalid operation", sourceMap);
        assertEquals(1, diagnostics.size());
        assertEquals(9, diagnostics.getFirst().sourceId());
        assertNull(diagnostics.getFirst().sourcePath());
    }

    @Test
    void returnsNoStructuredDiagnosticsForUnrecognizedDriverLogs() {
        assertTrue(parser.parse("vendor-specific compiler failure", sourceMap).isEmpty());
    }
}
