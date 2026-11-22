package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackFileBrowserModelTest {
    private static final List<String> FILES = List.of(
            "textures/noise.png",
            "shaders.properties",
            "world0/gbuffers_terrain.fsh",
            "world0/gbuffers_terrain.vsh",
            "lib/common.glsl",
            "lang/en_us.lang",
            "composite.fsh",
            "options.properties",
            "README"
    );

    @Test
    void allFilesAreSortedCaseInsensitivelyWithDeterministicTies() {
        assertEquals(List.of(
                "composite.fsh",
                "lang/en_us.lang",
                "lib/common.glsl",
                "options.properties",
                "README",
                "shaders.properties",
                "textures/noise.png",
                "world0/gbuffers_terrain.fsh",
                "world0/gbuffers_terrain.vsh"
        ), ShaderPackFileBrowserModel.apply(FILES, "", ShaderPackFileBrowserModel.FileFilter.ALL));
    }

    @Test
    void shaderFilterIncludesStagesAndGlslIncludes() {
        assertEquals(List.of(
                "composite.fsh",
                "lib/common.glsl",
                "world0/gbuffers_terrain.fsh",
                "world0/gbuffers_terrain.vsh"
        ), ShaderPackFileBrowserModel.apply(
                FILES, null, ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES));
    }

    @Test
    void propertiesFilterIncludesOnlyPropertiesFiles() {
        assertEquals(List.of("options.properties", "shaders.properties"),
                ShaderPackFileBrowserModel.apply(
                        FILES, "", ShaderPackFileBrowserModel.FileFilter.PROPERTIES));
    }

    @Test
    void assetsFilterExcludesShaderAndPropertiesFiles() {
        assertEquals(List.of("lang/en_us.lang", "README", "textures/noise.png"),
                ShaderPackFileBrowserModel.apply(
                        FILES, "", ShaderPackFileBrowserModel.FileFilter.ASSETS));
    }

    @Test
    void queryMatchesEveryTermAcrossPathAndExtensionWithoutCaseSensitivity() {
        assertEquals(List.of("textures/noise.png"),
                ShaderPackFileBrowserModel.apply(
                        FILES, "TEXTURES png", ShaderPackFileBrowserModel.FileFilter.ALL));
        assertTrue(ShaderPackFileBrowserModel.apply(
                FILES, "missing", ShaderPackFileBrowserModel.FileFilter.ALL).isEmpty());
    }

    @Test
    void queryAndFileKindFilterAreCombined() {
        assertEquals(List.of("world0/gbuffers_terrain.fsh"),
                ShaderPackFileBrowserModel.apply(
                        FILES, "world0 terrain", ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES));
    }

    @Test
    void nullAndWhitespaceQueriesBehaveLikeAnEmptyQuery() {
        assertEquals(
                ShaderPackFileBrowserModel.apply(FILES, "", ShaderPackFileBrowserModel.FileFilter.ALL),
                ShaderPackFileBrowserModel.apply(FILES, "   \t ", ShaderPackFileBrowserModel.FileFilter.ALL));
        assertEquals(
                ShaderPackFileBrowserModel.apply(FILES, "", ShaderPackFileBrowserModel.FileFilter.ALL),
                ShaderPackFileBrowserModel.apply(FILES, null, ShaderPackFileBrowserModel.FileFilter.ALL));
    }

    @Test
    void resultsAreImmutableAndInputsAreRequired() {
        List<String> result = ShaderPackFileBrowserModel.apply(
                FILES, "", ShaderPackFileBrowserModel.FileFilter.ALL);
        assertThrows(UnsupportedOperationException.class, () -> result.add("later.fsh"));
        assertThrows(NullPointerException.class,
                () -> ShaderPackFileBrowserModel.apply(null, "", ShaderPackFileBrowserModel.FileFilter.ALL));
        assertThrows(NullPointerException.class,
                () -> ShaderPackFileBrowserModel.apply(FILES, "", null));
    }

    @Test
    void fileFilterCyclesThroughEveryMode() {
        var filter = ShaderPackFileBrowserModel.FileFilter.ALL;
        filter = filter.next();
        assertEquals(ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES, filter);
        filter = filter.next();
        assertEquals(ShaderPackFileBrowserModel.FileFilter.PROPERTIES, filter);
        filter = filter.next();
        assertEquals(ShaderPackFileBrowserModel.FileFilter.ASSETS, filter);
        assertEquals(ShaderPackFileBrowserModel.FileFilter.ALL, filter.next());
    }
}
