package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GlslPreprocessorTest {
    @TempDir
    Path temporaryDirectory;

    private final GlslPreprocessor preprocessor = new GlslPreprocessor();

    @Test
    void expandsNestedIncludesAndPreservesSourceLineBoundaries() throws IOException {
        Map<String, String> files = Map.of(
                "gbuffers_terrain.vsh", "#version 120\n#include \"lib/common.glsl\"\nvoid main() {}",
                "lib/common.glsl", "vec3 commonColor() { return vec3(1.0); }\n#include <shared/math.glsl>",
                "shared/math.glsl", "float saturate(float x) { return clamp(x, 0.0, 1.0); }"
        );

        GlslPreprocessor.Result result = preprocessor.preprocess("gbuffers_terrain.vsh", path -> require(files, path));

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
        GlslPreprocessor.Result result = preprocessor.preprocess("passes/final.fsh", path -> require(files, path));
        assertTrue(result.source().contains("float localValue"));
        assertTrue(result.source().contains("float rootValue"));
    }

    @Test
    void capturesExtensionsAndRejectsConflictingDeclarations() throws IOException {
        GlslPreprocessor.Result result = preprocessor.preprocess("final.fsh", path -> require(Map.of(
                "final.fsh", "#version 330\n#extension GL_ARB_shading_language_420pack : enable\nvoid main() {}"
        ), path));

        assertEquals(1, result.extensions().size());
        assertEquals("GL_ARB_shading_language_420pack", result.extensions().getFirst().name());
        assertEquals("enable", result.extensions().getFirst().behavior());

        assertThrows(IOException.class, () -> preprocessor.preprocess("final.fsh", path -> require(Map.of(
                "final.fsh", "#extension GL_EXT_demo : enable\n#include \"other.glsl\"",
                "other.glsl", "#extension GL_EXT_demo : require"
        ), path)));
    }

    @Test
    void rejectsVersionDirectiveInsideIncludedFile() {
        assertThrows(IOException.class, () -> preprocessor.preprocess("final.fsh", path -> require(Map.of(
                "final.fsh", "#version 120\n#include \"bad.glsl\"",
                "bad.glsl", "#version 330"
        ), path)));
    }

    @Test
    void detectsIncludeCycles() {
        IOException exception = assertThrows(IOException.class, () -> preprocessor.preprocess("a.glsl", path -> require(Map.of(
                "a.glsl", "#include \"b.glsl\"",
                "b.glsl", "#include \"a.glsl\""
        ), path)));
        assertTrue(exception.getMessage().contains("include cycle"));
    }

    @Test
    void rejectsIncludesThatEscapeShaderRoot() {
        assertThrows(IOException.class, () -> preprocessor.preprocess("final.fsh",
                path -> require(Map.of("final.fsh", "#include \"../../outside.glsl\""), path)));
    }

    @Test
    void pragmaOnceSuppressesRepeatedAndRecursiveIncludes() throws IOException {
        GlslPreprocessor.Result result = preprocessor.preprocess("final.fsh", path -> require(Map.of(
                "final.fsh", "#include \"shared.glsl\"\n#include \"shared.glsl\"",
                "shared.glsl", "#pragma once\n#include \"shared.glsl\"\nconst float SHARED = 1.0;"
        ), path));
        assertEquals(1, occurrences(result.source(), "const float SHARED"));
    }

    @Test
    void rejectsOversizedIndividualSources() {
        String oversized = "x".repeat(GlslPreprocessor.MAX_SOURCE_BYTES + 1);
        IOException exception = assertThrows(IOException.class,
                () -> preprocessor.preprocess("large.glsl", path -> oversized));
        assertTrue(exception.getMessage().contains("source exceeds"));
    }

    @Test
    void readsDirectoryPackThroughSafeSourceAdapter() throws IOException {
        Path shaderDirectory = Files.createDirectories(temporaryDirectory.resolve("DirectoryPack/shaders"));
        Files.writeString(shaderDirectory.resolve("final.fsh"), "#version 120\nvoid main() {}");
        ShaderPackDescriptor descriptor = descriptor(
                temporaryDirectory.resolve("DirectoryPack"), "", ShaderPackSourceType.DIRECTORY);

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            GlslPreprocessor.Result result = preprocessor.preprocess("final.fsh", sources);
            assertTrue(result.source().contains("void main()"));
            assertEquals("#version 120", result.versionDirective());
        }
    }

    @Test
    void readsWrappedZipPackWithoutExtractingIt() throws IOException {
        Path archive = temporaryDirectory.resolve("Wrapped.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Wrapped/shaders/final.fsh"));
            zip.write("#version 330\nvoid main() {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ShaderPackDescriptor descriptor = descriptor(archive, "Wrapped", ShaderPackSourceType.ZIP_ARCHIVE);

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            GlslPreprocessor.Result result = preprocessor.preprocess("final.fsh", sources);
            assertTrue(result.source().contains("void main()"));
            assertEquals("#version 330", result.versionDirective());
        }
    }

    private ShaderPackDescriptor descriptor(Path source, String shaderRoot, ShaderPackSourceType sourceType) {
        return new ShaderPackDescriptor("test-pack", "Test Pack", source, shaderRoot,
                sourceType, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 64);
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
