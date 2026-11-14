package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlslPreprocessorTest {
    private final GlslPreprocessor preprocessor = new GlslPreprocessor();

    @Test
    void expandsNestedIncludesAndPreservesSourceLineBoundaries() throws IOException {
        Map<String, String> files = Map.of(
                "gbuffers_terrain.vsh", "#version 120\n#include \"lib/common.glsl\"\nvoid main() {}",
                "lib/common.glsl", "vec3 commonColor() { return vec3(1.0); }\n#include <shared/math.glsl>",
                "shared/math.glsl", "float saturate(float x) { return clamp(x, 0.0, 1.0); }"
        );

        GlslPreprocessor.Result result = preprocessor.preprocess(
                "gbuffers_terrain.vsh",
                path -> require(files, path)
        );

        assertEquals("#version 120", result.versionDirective());
        assertTrue(result.source().startsWith("#version 120\n"));
        assertTrue(result.source().contains("vec3 commonColor()"));
        assertTrue(result.source().contains("float saturate(float x)"));
        assertTrue(result.source().contains("#line 1 1"));
        assertTrue(result.source().contains("#line 3 0"));
        assertEquals(3, result.sourceMap().size());
        assertEquals(3, result.includedFiles().size());
    }

    @Test
    void resolvesQuotedIncludesRelativeToCurrentFileThenPackRoot() throws IOException {
        Map<String, String> files = Map.of(
                "passes/final.fsh", "#include \"local.glsl\"\n#include \"root.glsl\"",
                "passes/local.glsl", "float localValue = 1.0;",
                "root.glsl", "float rootValue = 2.0;"
        );

        GlslPreprocessor.Result result = preprocessor.preprocess(
                "passes/final.fsh",
                path -> require(files, path)
        );

        assertTrue(result.source().contains("float localValue"));
        assertTrue(result.source().contains("float rootValue"));
    }

    @Test
    void capturesExtensionsAndRejectsConflictingDeclarations() throws IOException {
        GlslPreprocessor.Result result = preprocessor.preprocess(
                "final.fsh",
                path -> require(Map.of(
                        "final.fsh", "#version 330\n#extension GL_ARB_shading_language_420pack : enable\nvoid main() {}"
                ), path)
        );

        assertEquals(1, result.extensions().size());
        assertEquals("GL_ARB_shading_language_420pack", result.extensions().getFirst().name());
        assertEquals("enable", result.extensions().getFirst().behavior());

        assertThrows(IOException.class, () -> preprocessor.preprocess(
                "final.fsh",
                path -> require(Map.of(
                        "final.fsh", "#extension GL_EXT_demo : enable\n#include \"other.glsl\"",
                        "other.glsl", "#extension GL_EXT_demo : require"
                ), path)
        ));
    }

    @Test
    void rejectsVersionDirectiveInsideIncludedFile() {
        assertThrows(IOException.class, () -> preprocessor.preprocess(
                "final.fsh",
                path -> require(Map.of(
                        "final.fsh", "#version 120\n#include \"bad.glsl\"",
                        "bad.glsl", "#version 330"
                ), path)
        ));
    }

    @Test
    void detectsIncludeCycles() {
        IOException exception = assertThrows(IOException.class, () -> preprocessor.preprocess(
                "a.glsl",
                path -> require(Map.of(
                        "a.glsl", "#include \"b.glsl\"",
                        "b.glsl", "#include \"a.glsl\""
                ), path)
        ));
        assertTrue(exception.getMessage().contains("include cycle"));
    }

    @Test
    void rejectsIncludesThatEscapeShaderRoot() {
        assertThrows(IOException.class, () -> preprocessor.preprocess(
                "final.fsh",
                path -> require(Map.of("final.fsh", "#include \"../../outside.glsl\""), path)
        ));
    }

    @Test
    void pragmaOnceSuppressesRepeatedIncludes() throws IOException {
        GlslPreprocessor.Result result = preprocessor.preprocess(
                "final.fsh",
                path -> require(Map.of(
                        "final.fsh", "#include \"shared.glsl\"\n#include \"shared.glsl\"",
                        "shared.glsl", "#pragma once\nconst float SHARED = 1.0;"
                ), path)
        );

        assertEquals(1, occurrences(result.source(), "const float SHARED"));
    }

    @Test
    void rejectsOversizedIndividualSources() {
        String oversized = "x".repeat(GlslPreprocessor.MAX_SOURCE_BYTES + 1);
        IOException exception = assertThrows(IOException.class, () -> preprocessor.preprocess(
                "large.glsl",
                path -> oversized
        ));
        assertTrue(exception.getMessage().contains("source exceeds"));
    }

    private static String require(Map<String, String> files, String path) throws IOException {
        String source = files.get(path);
        if (source == null) {
            throw new IOException("Missing test source: " + path);
        }
        return source;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
