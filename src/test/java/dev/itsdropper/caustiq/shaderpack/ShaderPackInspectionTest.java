package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ShaderPackInspectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectsDirectoryInventoryAndDiscoversInlinePackOptions() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path shaders = Files.createDirectories(packs.resolve("ExamplePack/shaders"));
        Files.writeString(shaders.resolve("final.fsh"),
                "#define QUALITY 1 // [0 1 2]\nvoid main() {}\n");
        Files.writeString(shaders.resolve("shaders.properties"), "screen=QUALITY\nsliders=QUALITY\n");
        Files.createDirectories(shaders.resolve("textures"));
        Files.write(shaders.resolve("textures/noise.png"), new byte[]{1, 2, 3});

        ShaderPackInspection inspection = ShaderPackInspection.inspect(discoverOnlyPack(packs));

        assertEquals(List.of("final.fsh", "shaders.properties", "textures/noise.png"), inspection.files());
        assertEquals(1, inspection.pack().shaderFileCount());
        assertEquals(1, inspection.pack().pipelineEntryPointCount());
        assertEquals(1, inspection.metadataOptionCount());
        assertTrue(inspection.metadataFailure().isEmpty());

        String report = inspection.createReport();
        assertTrue(report.contains("Pack options: 1"));
        assertTrue(report.contains("shaders/textures/noise.png"));
        assertFalse(report.contains(temporaryDirectory.toString()));
        assertFalse(report.contains("void main"));
    }

    @Test
    void inspectsWrappedZipWithoutExtractingOrIncludingWrapperDirectoryInPaths() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("WrappedPack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "WrappedPack/shaders/final.fsh", "void main() {}");
            addEntry(zip, "WrappedPack/shaders/shaders.properties", "screen=QUALITY\noption.QUALITY=LOW HIGH\n");
            addEntry(zip, "WrappedPack/shaders/textures/noise.png", "asset");
        }

        ShaderPackInspection inspection = ShaderPackInspection.inspect(discoverOnlyPack(packs));

        assertEquals(List.of("final.fsh", "shaders.properties", "textures/noise.png"), inspection.files());
        assertEquals(1, inspection.metadataOptionCount());
        assertTrue(inspection.createReport().contains("ZIP ARCHIVE (WrappedPack.zip)"));
        assertFalse(inspection.createReport().contains(temporaryDirectory.toString()));
        assertTrue(Files.exists(archive));
    }

    @Test
    void metadataFailureDoesNotDiscardTheUsableFileInventory() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path shaders = Files.createDirectories(packs.resolve("BrokenMetadata/shaders"));
        Files.writeString(shaders.resolve("final.fsh"), "void main() {}");
        Files.writeString(shaders.resolve("shaders.properties"), "x".repeat(256 * 1024 + 1));

        ShaderPackInspection inspection = ShaderPackInspection.inspect(discoverOnlyPack(packs));

        assertEquals(-1, inspection.metadataOptionCount());
        assertFalse(inspection.metadataFailure().isBlank());
        assertTrue(inspection.files().contains("final.fsh"));
        assertTrue(inspection.createReport().contains("Pack options: unavailable"));
    }

    @Test
    void copiedReportBoundsInventoryAndNeverIncludesTheAbsolutePackPath() {
        ShaderPackDescriptor pack = new ShaderPackDescriptor(
                "test-pack", "Test Pack", temporaryDirectory.resolve("private/game/shaderpacks/Test Pack"),
                "", ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        List<String> files = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            files.add("file-" + index + ".glsl");
        }
        ShaderPackInspection inspection = new ShaderPackInspection(pack, files, 0, "");

        String report = inspection.createReport();

        assertTrue(report.contains("file-199.glsl"));
        assertFalse(report.contains("file-200.glsl"));
        assertTrue(report.contains("5 additional file(s) omitted"));
        assertFalse(report.contains(temporaryDirectory.toString()));
        assertTrue(report.contains("No absolute game-directory paths"));
    }


    @Test
    void filteredPathReportIncludesOnlyKnownRelativeEntriesAndRemovesDuplicates() {
        ShaderPackDescriptor pack = new ShaderPackDescriptor(
                "test-pack", "Test Pack", temporaryDirectory.resolve("private/game/shaderpacks/Test Pack"),
                "", ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        ShaderPackInspection inspection = new ShaderPackInspection(
                pack, List.of("final.fsh", "textures/noise.png"), 0, "");

        String report = inspection.createFileListReport(List.of(
                "textures/noise.png", "/home/player/private.txt", "../escape", "textures/noise.png"));

        assertTrue(report.startsWith("CAUSTIQ Shader Pack File Paths\nPack: Test Pack\n"));
        assertTrue(report.contains("Matching files: 1"));
        assertTrue(report.contains("- textures/noise.png"));
        assertFalse(report.contains("/home/player"));
        assertFalse(report.contains("../escape"));
        assertFalse(report.contains("final.fsh"));
        assertTrue(report.contains("Paths are relative to shaders/"));
    }

    @Test
    void filteredPathReportBoundsLargeInventories() {
        ShaderPackDescriptor pack = new ShaderPackDescriptor(
                "test-pack", "Test Pack", temporaryDirectory.resolve("private/game/shaderpacks/Test Pack"),
                "", ShaderPackSourceType.DIRECTORY, ShaderPackLayout.OPTIFINE_STYLE, 1, 1, 0);
        List<String> files = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            files.add("file-" + index + ".glsl");
        }
        ShaderPackInspection inspection = new ShaderPackInspection(pack, files, 0, "");

        String report = inspection.createFileListReport(files);

        assertTrue(report.contains("file-199.glsl"));
        assertFalse(report.contains("file-200.glsl"));
        assertTrue(report.contains("5 additional file(s) omitted"));
        assertFalse(report.contains(temporaryDirectory.toString()));
    }

    private ShaderPackDescriptor discoverOnlyPack(Path packs) throws IOException {
        ShaderPackScanResult result = new ShaderPackScanner().scan(packs);
        assertEquals(1, result.packs().size(), () -> "Unexpected scan issues: " + result.issues());
        return result.packs().getFirst();
    }

    private static void addEntry(ZipOutputStream zip, String path, String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
