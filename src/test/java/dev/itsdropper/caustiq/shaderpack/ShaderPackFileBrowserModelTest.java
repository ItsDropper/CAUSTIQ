package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
            "README",
            "folder.properties/readme"
    );

    @Test
    void allFilesAreSortedCaseInsensitivelyWithDeterministicTies() {
        assertEquals(List.of(
                "composite.fsh",
                "folder.properties/readme",
                "lang/en_us.lang",
                "lib/common.glsl",
                "options.properties",
                "README",
                "shaders.properties",
                "textures/noise.png",
                "world0/gbuffers_terrain.fsh",
                "world0/gbuffers_terrain.vsh"
        ), browser().apply("", ShaderPackFileBrowserModel.FileFilter.ALL));
    }

    @Test
    void caseOnlyPathTiesUseNaturalOrder() {
        ShaderPackFileBrowserModel model = new ShaderPackFileBrowserModel(
                List.of("world/file.fsh", "World/file.fsh"));

        assertEquals(List.of("World/file.fsh", "world/file.fsh"),
                model.apply("", ShaderPackFileBrowserModel.FileFilter.ALL));
    }

    @Test
    void shaderFilterIncludesStagesAndGlslIncludes() {
        assertEquals(List.of(
                "composite.fsh",
                "lib/common.glsl",
                "world0/gbuffers_terrain.fsh",
                "world0/gbuffers_terrain.vsh"
        ), browser().apply(null, ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES));
    }

    @Test
    void propertiesFilterIncludesOnlyPropertiesFiles() {
        assertEquals(List.of("options.properties", "shaders.properties"),
                browser().apply("", ShaderPackFileBrowserModel.FileFilter.PROPERTIES));
    }

    @Test
    void assetsFilterExcludesShaderAndPropertiesFiles() {
        assertEquals(List.of("folder.properties/readme", "lang/en_us.lang", "README", "textures/noise.png"),
                browser().apply("", ShaderPackFileBrowserModel.FileFilter.ASSETS));
    }

    @Test
    void queryMatchesEveryTermAcrossPathAndExtensionWithoutCaseSensitivity() {
        assertEquals(List.of("textures/noise.png"),
                browser().apply("TEXTURES png", ShaderPackFileBrowserModel.FileFilter.ALL));
        assertTrue(browser().apply("missing", ShaderPackFileBrowserModel.FileFilter.ALL).isEmpty());
    }

    @Test
    void queryAndFileKindFilterAreCombined() {
        assertEquals(List.of(
                "world0/gbuffers_terrain.fsh",
                "world0/gbuffers_terrain.vsh"
        ), browser().apply("world0 terrain", ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES));
    }

    @Test
    void nullAndWhitespaceQueriesBehaveLikeAnEmptyQuery() {
        assertEquals(
                browser().apply("", ShaderPackFileBrowserModel.FileFilter.ALL),
                browser().apply("   \t ", ShaderPackFileBrowserModel.FileFilter.ALL));
        assertEquals(
                browser().apply("", ShaderPackFileBrowserModel.FileFilter.ALL),
                browser().apply(null, ShaderPackFileBrowserModel.FileFilter.ALL));
    }

    @Test
    void inventoryIsSnapshottedAndResultsAreImmutable() {
        List<String> input = new ArrayList<>(List.of("B/file.FSH", "a/file.fsh"));
        ShaderPackFileBrowserModel model = new ShaderPackFileBrowserModel(input);
        input.clear();

        List<String> result = model.apply("FILE fsh", ShaderPackFileBrowserModel.FileFilter.SHADER_SOURCES);
        assertEquals(List.of("a/file.fsh", "B/file.FSH"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("later.fsh"));
    }

    @Test
    void constructorAndFilterArgumentsAreRequired() {
        assertThrows(NullPointerException.class, () -> new ShaderPackFileBrowserModel(null));
        assertThrows(NullPointerException.class,
                () -> new ShaderPackFileBrowserModel(java.util.Arrays.asList("valid.fsh", null)));
        assertThrows(NullPointerException.class, () -> browser().apply("", null));
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

    private static ShaderPackFileBrowserModel browser() {
        return new ShaderPackFileBrowserModel(FILES);
    }
}
