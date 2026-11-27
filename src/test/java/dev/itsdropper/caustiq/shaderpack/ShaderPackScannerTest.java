package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ShaderPackScannerTest {
    @TempDir
    Path temporaryDirectory;

    private final ShaderPackScanner scanner = new ShaderPackScanner();

    @Test
    void discoversDirectoryPackAndCountsPipelineFiles() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path pack = Files.createDirectories(packs.resolve("ExamplePack/shaders"));
        Files.writeString(pack.resolve("gbuffers_terrain.vsh"), "#version 120\nvoid main() {}");
        Files.writeString(pack.resolve("gbuffers_terrain.fsh"), "#version 120\nvoid main() {}");
        Files.writeString(pack.resolve("composite.fsh"), "#version 120\nvoid main() {}");
        Files.writeString(pack.resolve("readme.txt"), "not a shader stage");

        ShaderPackScanResult result = scanner.scan(packs);

        assertEquals(1, result.packs().size());
        assertTrue(result.issues().isEmpty());
        ShaderPackDescriptor descriptor = result.packs().getFirst();
        assertEquals("ExamplePack", descriptor.displayName());
        assertEquals(ShaderPackSourceType.DIRECTORY, descriptor.sourceType());
        assertEquals(3, descriptor.shaderFileCount());
        assertEquals(3, descriptor.pipelineEntryPointCount());
        assertEquals("", descriptor.shaderRoot());
        assertEquals(ShaderPackLayout.OPTIFINE_STYLE, descriptor.layout());
    }

    @Test
    void discoversZipWithSingleWrapperDirectory() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("WrappedPack.zip");
        writeZip(archive, List.of(
                new ZipContent("WrappedPack/shaders/gbuffers_terrain.vsh", "void main() {}"),
                new ZipContent("WrappedPack/shaders/final.fsh", "void main() {}"),
                new ZipContent("WrappedPack/pack.mcmeta", "{}")
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertEquals(1, result.packs().size());
        assertTrue(result.issues().isEmpty());
        assertEquals("WrappedPack", result.packs().getFirst().displayName());
        assertEquals("WrappedPack", result.packs().getFirst().shaderRoot());
        assertEquals(2, result.packs().getFirst().pipelineEntryPointCount());
        assertEquals(ShaderPackSourceType.ZIP_ARCHIVE, result.packs().getFirst().sourceType());
    }

    @Test
    void ignoresUnrelatedFilesAndHiddenDirectories() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Files.writeString(packs.resolve("notes.txt"), "not a pack");
        Files.createDirectory(packs.resolve(".hidden"));
        Files.createDirectories(packs.resolve(".hidden/shaders"));
        Files.writeString(packs.resolve(".hidden/shaders/final.fsh"), "void main() {}");

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertTrue(result.issues().isEmpty());
        assertEquals(0, result.candidatesInspected());
    }

    @Test
    void rejectsZipPathTraversal() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("Traversal.zip");
        writeZip(archive, List.of(
                new ZipContent("../outside.txt", "no"),
                new ZipContent("shaders/final.fsh", "void main() {}")
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("unsafe entry path"));
    }

    @Test
    void rejectsZipBombUsingCentralDirectoryMetadata() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("CompressedBomb.zip");
        byte[] repeated = new byte[4 * 1024 * 1024];
        writeZip(archive, List.of(
                new ZipContent("shaders/final.fsh", "void main() {}"),
                new ZipContent("shaders/large.bin", repeated)
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("compression ratio"));
    }

    @Test
    void rejectsArchivesWithoutRecognizedPipelineEntryPoints() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("NotAShaderPack.zip");
        writeZip(archive, List.of(
                new ZipContent("shaders/readme.txt", "documentation"),
                new ZipContent("shaders/custom.glsl", "void main() {}")
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("pipeline entry points"));
    }

    @Test
    void rejectsMultipleShaderRootsAsAmbiguous() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("Ambiguous.zip");
        writeZip(archive, List.of(
                new ZipContent("First/shaders/final.fsh", "void main() {}"),
                new ZipContent("Second/shaders/final.fsh", "void main() {}")
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("Multiple shaders"));
    }

    @Test
    void rejectsDuplicateCaseCollidingArchivePaths() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Path archive = packs.resolve("Duplicate.zip");
        writeZip(archive, List.of(
                new ZipContent("shaders/final.fsh", "void main() {}"),
                new ZipContent("SHADERS/final.fsh", "void main() {}")
        ));

        ShaderPackScanResult result = scanner.scan(packs);

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("duplicate or case-colliding"));
    }

    @Test
    void reportsMissingOrInvalidShaderPackDirectory() {
        ShaderPackScanResult result = scanner.scan(temporaryDirectory.resolve("missing"));

        assertTrue(result.packs().isEmpty());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("does not exist"));
    }

    @Test
    void reportsMalformedZipWithoutAbortingTheWholeScan() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("shaderpacks"));
        Files.writeString(packs.resolve("Broken.zip"), "this is not a ZIP archive");

        Path validPack = Files.createDirectories(packs.resolve("Valid/shaders"));
        Files.writeString(validPack.resolve("final.fsh"), "void main() {}");

        ShaderPackScanResult result = scanner.scan(packs);

        assertEquals(1, result.packs().size());
        assertEquals("Valid", result.packs().getFirst().displayName());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().getFirst().reason().contains("ZIP"));
        assertEquals(2, result.candidatesInspected());
    }


    @Test
    void reportsFilenameOnlyProgressForEveryCandidateIncludingRejectedArchives() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("progress-packs"));
        Path valid = Files.createDirectories(packs.resolve("ValidPack/shaders"));
        Files.writeString(valid.resolve("final.fsh"), "void main() {}");
        Files.writeString(packs.resolve("Broken.zip"), "not a zip archive");

        List<ShaderPackScanProgress> progress = new java.util.ArrayList<>();
        ShaderPackScanResult result = scanner.scan(packs, progress::add);

        assertEquals(1, result.packs().size());
        assertEquals(1, result.issues().size());
        assertEquals(2, result.candidatesInspected());
        assertEquals(4, progress.size());
        assertEquals(ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES, progress.get(0).stage());
        assertEquals(0, progress.get(0).totalCandidates());
        assertEquals(ShaderPackScanProgress.inspectingCandidates(0, 2, "Broken.zip"), progress.get(1));
        assertEquals(ShaderPackScanProgress.inspectingCandidates(1, 2, "ValidPack"), progress.get(2));
        assertEquals(ShaderPackScanProgress.inspectingCandidates(2, 2, ""), progress.get(3));
        assertTrue(progress.stream().noneMatch(update ->
                update.currentCandidate().contains("/") || update.currentCandidate().indexOf((char) 92) >= 0));
    }

    @Test
    void cancellationDuringCandidateDiscoveryStopsBeforeInspectingCandidates() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("cancel-discovery"));
        Path shaders = Files.createDirectories(packs.resolve("ValidPack/shaders"));
        Files.writeString(shaders.resolve("final.fsh"), "void main() {}");
        AtomicBoolean cancellation = new AtomicBoolean();

        assertThrows(CancellationException.class, () -> scanner.scan(packs, progress -> {
            if (progress.stage() == ShaderPackScanProgress.Stage.DISCOVERING_CANDIDATES) {
                cancellation.set(true);
            }
        }, cancellation::get));
    }

    @Test
    void cancellationDuringDirectoryTraversalStopsBetweenEntries() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("cancel-directory"));
        Path assets = Files.createDirectories(packs.resolve("LargePack/shaders/assets"));
        Files.writeString(packs.resolve("LargePack/shaders/final.fsh"), "void main() {}");
        for (int index = 0; index < 128; index++) {
            Files.writeString(assets.resolve("asset-" + index + ".bin"), "asset");
        }
        AtomicInteger checks = new AtomicInteger();

        assertThrows(CancellationException.class,
                () -> scanner.scan(packs, ignored -> { }, () -> checks.incrementAndGet() > 20));
        assertTrue(checks.get() > 20, "Cancellation should be observed during recursive directory traversal");
    }

    @Test
    void cancellationDuringArchiveInspectionStopsBetweenZipEntries() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("cancel-archive"));
        Path archive = packs.resolve("LargePack.zip");
        List<ZipContent> entries = new java.util.ArrayList<>();
        entries.add(new ZipContent("shaders/final.fsh", "void main() {}"));
        for (int index = 0; index < 128; index++) {
            entries.add(new ZipContent("shaders/assets/asset-" + index + ".bin", "asset"));
        }
        writeZip(archive, entries);
        AtomicInteger checks = new AtomicInteger();

        assertThrows(CancellationException.class,
                () -> scanner.scan(packs, ignored -> { }, () -> checks.incrementAndGet() > 20));
        assertTrue(checks.get() > 20, "Cancellation should be observed while enumerating ZIP entries");
    }

    @Test
    void cancellationAfterOneCandidateDiscardsThePartialScan() throws IOException {
        Path packs = Files.createDirectory(temporaryDirectory.resolve("cancel-partial"));
        for (String name : List.of("A", "B")) {
            Path shaders = Files.createDirectories(packs.resolve(name).resolve("shaders"));
            Files.writeString(shaders.resolve("final.fsh"), "void main() {}");
        }
        AtomicBoolean cancellation = new AtomicBoolean();

        assertThrows(CancellationException.class, () -> scanner.scan(packs, progress -> {
            if (progress.stage() == ShaderPackScanProgress.Stage.INSPECTING_CANDIDATES
                    && progress.completedCandidates() == 1) {
                cancellation.set(true);
            }
        }, cancellation::get));
    }

    private void writeZip(Path destination, List<ZipContent> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(destination);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (ZipContent content : entries) {
                zip.putNextEntry(new ZipEntry(content.path()));
                if (content.bytes() != null) {
                    zip.write(content.bytes());
                } else {
                    zip.write(content.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
    }

    private record ZipContent(String path, String text, byte[] bytes) {
        private ZipContent(String path, String text) {
            this(path, text, null);
        }

        private ZipContent(String path, byte[] bytes) {
            this(path, null, bytes);
        }
    }
}
