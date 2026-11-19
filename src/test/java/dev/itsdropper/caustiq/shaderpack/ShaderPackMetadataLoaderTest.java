package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackMetadataLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsDirectoryOptionsAndInlineDefaults() throws IOException {
        Path packRoot = Files.createDirectory(temporaryDirectory.resolve("DirectoryPack"));
        Path shaders = Files.createDirectory(packRoot.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                screen=QUALITY
                sliders=QUALITY
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #define QUALITY 1 // [0 1 2]
                void main() {}
                """);

        ShaderPackMetadata metadata = ShaderPackMetadataLoader.load(directoryPack(packRoot));

        assertEquals(1, metadata.options().size());
        ShaderPackMetadata.Option option = metadata.options().getFirst();
        assertEquals("QUALITY", option.name());
        assertEquals("1", option.defaultValue());
        assertEquals(List.of("0", "1", "2"), option.values());
        assertTrue(option.slider());
    }

    @Test
    void loadsOptionsFromWrappedZipWithoutExtractingIt() throws IOException {
        Path archive = temporaryDirectory.resolve("WrappedPack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "WrappedPack/shaders/shaders.properties", """
                    screen=SHADOW_MODE
                    option.SHADOW_MODE=OFF ON
                    """);
            addEntry(zip, "WrappedPack/shaders/final.fsh", "void main() {}");
        }
        ShaderPackDescriptor pack = new ShaderPackDescriptor(
                "wrapped-pack", "Wrapped Pack", archive, "WrappedPack",
                ShaderPackSourceType.ZIP_ARCHIVE, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 64);

        ShaderPackMetadata metadata = ShaderPackMetadataLoader.load(pack);

        assertEquals(1, metadata.options().size());
        assertEquals("OFF", metadata.options().getFirst().defaultValue());
        assertTrue(Files.isRegularFile(archive));
    }

    @Test
    void loadsInlineOptionsWhenPropertiesFileIsAbsent() throws IOException {
        Path packRoot = Files.createDirectory(temporaryDirectory.resolve("InlinePack"));
        Path shaders = Files.createDirectory(packRoot.resolve("shaders"));
        Files.writeString(shaders.resolve("final.fsh"), """
                #define ENABLE_FOG true // [true false]
                void main() {}
                """);

        ShaderPackMetadata metadata = ShaderPackMetadataLoader.load(directoryPack(packRoot));

        assertEquals(1, metadata.options().size());
        assertEquals("ENABLE_FOG", metadata.options().getFirst().name());
        assertEquals("true", metadata.options().getFirst().defaultValue());
    }

    @Test
    void rejectsOversizedPropertiesInsteadOfSilentlyTreatingThemAsMissing() throws IOException {
        Path packRoot = Files.createDirectory(temporaryDirectory.resolve("OversizedPack"));
        Path shaders = Files.createDirectory(packRoot.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "x".repeat(256 * 1024 + 1));
        Files.writeString(shaders.resolve("final.fsh"), "void main() {}");

        assertThrows(IllegalArgumentException.class,
                () -> ShaderPackMetadataLoader.load(directoryPack(packRoot)));
    }

    private static ShaderPackDescriptor directoryPack(Path root) {
        return new ShaderPackDescriptor(
                root.getFileName().toString().toLowerCase(java.util.Locale.ROOT),
                root.getFileName().toString(), root, "",
                ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
    }

    private static void addEntry(ZipOutputStream zip, String path, String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
