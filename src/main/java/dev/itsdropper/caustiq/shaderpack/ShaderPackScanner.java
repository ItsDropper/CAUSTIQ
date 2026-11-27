package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers shader packs without extracting archives or following symbolic
 * links. Archive metadata is bounded before any pack is accepted.
 *
 * <p>The scanner deliberately performs structural inspection only. Shader
 * source compilation and feature compatibility belong to later pipeline
 * stages.</p>
 */
public final class ShaderPackScanner {
    private static final int MAX_CANDIDATES = 4_096;
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_DIRECTORY_DEPTH = 32;
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_SINGLE_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    private static final long MIN_RATIO_CHECK_BYTES = 1L * 1024 * 1024;
    private static final double MAX_COMPRESSION_RATIO = 200.0;

    private static final Pattern SHADER_FILE = Pattern.compile("(?i)^.+\\.(vsh|fsh|gsh|csh|tcs|tes)$");
    private static final Pattern PIPELINE_ENTRY_POINT = Pattern.compile(
            "(?i)^(gbuffers_[a-z0-9_]+|composite[0-9]*|deferred[0-9]*|final|" +
                    "shadow(comp)?[0-9]*|prepare[0-9]*|begin|setup|world|dh_[a-z0-9_]+)\\.(vsh|fsh|gsh|csh)$"
    );

    public ShaderPackScanResult scan(Path shaderPackDirectory) {
        return scan(shaderPackDirectory, ignored -> { }, () -> false);
    }

    /**
     * Scans without extracting archives and reports bounded, filename-only progress.
     * The listener is invoked on the calling thread and should not perform blocking work.
     */
    public ShaderPackScanResult scan(
            Path shaderPackDirectory,
            Consumer<ShaderPackScanProgress> progressListener
    ) {
        return scan(shaderPackDirectory, progressListener, () -> false);
    }

    /**
     * Scans with cooperative cancellation checked between directory and archive entries.
     * Cancellation discards the in-progress result rather than publishing a partial pack list.
     */
    public ShaderPackScanResult scan(
            Path shaderPackDirectory,
            Consumer<ShaderPackScanProgress> progressListener,
            BooleanSupplier cancellationRequested
    ) {
        Objects.requireNonNull(shaderPackDirectory, "shaderPackDirectory");
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        checkCancellation(cancellationRequested);
        progressListener.accept(ShaderPackScanProgress.discoveringCandidates());
        checkCancellation(cancellationRequested);

        Path directory = shaderPackDirectory.toAbsolutePath().normalize();
        List<ShaderPackDescriptor> packs = new ArrayList<>();
        List<ShaderPackIssue> issues = new ArrayList<>();

        if (Files.isSymbolicLink(directory)) {
            checkCancellation(cancellationRequested);
            issues.add(new ShaderPackIssue(directory, "Shader-pack directory must not be a symbolic link."));
            checkCancellation(cancellationRequested);
            return new ShaderPackScanResult(packs, issues, 0);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            checkCancellation(cancellationRequested);
            issues.add(new ShaderPackIssue(directory, "Shader-pack directory does not exist or is not a directory."));
            checkCancellation(cancellationRequested);
            return new ShaderPackScanResult(packs, issues, 0);
        }

        List<Path> candidates = new ArrayList<>(MAX_CANDIDATES + 1);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                checkCancellation(cancellationRequested);
                if (isCandidate(child)) {
                    candidates.add(child);
                    if (candidates.size() > MAX_CANDIDATES) {
                        break;
                    }
                }
            }
        } catch (IOException exception) {
            checkCancellation(cancellationRequested);
            issues.add(new ShaderPackIssue(directory, "Unable to list shader-pack directory: " + safeMessage(exception)));
            checkCancellation(cancellationRequested);
            return new ShaderPackScanResult(packs, issues, 0);
        }

        checkCancellation(cancellationRequested);
        candidates.sort(Comparator.comparing(
                (Path path) -> path.getFileName().toString(),
                String.CASE_INSENSITIVE_ORDER
        ).thenComparing(path -> path.getFileName().toString()));

        if (candidates.size() > MAX_CANDIDATES) {
            issues.add(new ShaderPackIssue(directory,
                    "Directory contains more than " + MAX_CANDIDATES + " shader-pack candidates; remaining entries were not scanned."));
            candidates = candidates.subList(0, MAX_CANDIDATES);
        }

        int totalCandidates = candidates.size();
        if (totalCandidates == 0) {
            progressListener.accept(ShaderPackScanProgress.inspectingCandidates(0, 0, ""));
        } else {
            progressListener.accept(ShaderPackScanProgress.inspectingCandidates(
                    0, totalCandidates, candidates.getFirst().getFileName().toString()));
        }

        int inspected = 0;
        for (Path candidate : candidates) {
            checkCancellation(cancellationRequested);
            try {
                ShaderPackDescriptor descriptor = inspect(candidate, cancellationRequested);
                checkCancellation(cancellationRequested);
                if (descriptor != null) {
                    packs.add(descriptor);
                }
            } catch (IOException | IllegalArgumentException exception) {
                issues.add(new ShaderPackIssue(candidate, safeMessage(exception)));
            }
            inspected++;
            String nextCandidate = inspected < totalCandidates
                    ? candidates.get(inspected).getFileName().toString() : "";
            progressListener.accept(ShaderPackScanProgress.inspectingCandidates(
                    inspected, totalCandidates, nextCandidate));
        }

        checkCancellation(cancellationRequested);
        return new ShaderPackScanResult(packs, issues, inspected);
    }

    private boolean isCandidate(Path path) {
        if (Files.isSymbolicLink(path)) {
            return false;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return !path.getFileName().toString().startsWith(".");
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                && !path.getFileName().toString().startsWith(".");
    }

    private ShaderPackDescriptor inspect(Path source, BooleanSupplier cancellationRequested) throws IOException {
        checkCancellation(cancellationRequested);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return inspectDirectory(source, cancellationRequested);
        }
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return inspectArchive(source, cancellationRequested);
        }
        throw new IOException("Candidate is not a regular directory or ZIP archive.");
    }

    private ShaderPackDescriptor inspectDirectory(Path source, BooleanSupplier cancellationRequested) throws IOException {
        checkCancellation(cancellationRequested);
        Set<String> filePaths = new HashSet<>();
        AtomicLong totalBytes = new AtomicLong();
        AtomicLong entryCount = new AtomicLong();

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkCancellation(cancellationRequested);
                if (attributes.isSymbolicLink()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(source) && source.relativize(directory).getNameCount() > MAX_DIRECTORY_DEPTH) {
                    throw new IOException("Directory pack exceeds the maximum nesting depth of " + MAX_DIRECTORY_DEPTH + ".");
                }
                countEntry(entryCount);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkCancellation(cancellationRequested);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                countEntry(entryCount);
                long size = attributes.size();
                validateEntrySize(size, file.getFileName().toString());
                addTotalBytes(totalBytes, size);
                filePaths.add(normalizeRelativePath(source.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });

        checkCancellation(cancellationRequested);
        return createDescriptor(source, ShaderPackSourceType.DIRECTORY, filePaths, totalBytes.get(), cancellationRequested);
    }

    private ShaderPackDescriptor inspectArchive(Path source, BooleanSupplier cancellationRequested) throws IOException {
        checkCancellation(cancellationRequested);
        long archiveSize = Files.size(source);
        if (archiveSize > MAX_ARCHIVE_BYTES) {
            throw new IOException("ZIP archive exceeds the compressed size limit of " + MAX_ARCHIVE_BYTES + " bytes.");
        }

        Set<String> filePaths = new HashSet<>();
        Set<String> normalizedPaths = new HashSet<>();
        AtomicLong totalBytes = new AtomicLong();
        int entryCount = 0;

        try (ZipFile zipFile = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                checkCancellation(cancellationRequested);
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("ZIP archive contains more than " + MAX_ENTRIES + " entries.");
                }

                String normalized = normalizeArchivePath(entry.getName());
                String collisionKey = normalized.toLowerCase(Locale.ROOT);
                if (!normalizedPaths.add(collisionKey)) {
                    throw new IOException("ZIP archive contains duplicate or case-colliding paths: " + normalized);
                }

                if (entry.isDirectory()) {
                    continue;
                }

                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                validateEntrySize(size, normalized);
                validateCompressionRatio(size, compressedSize, normalized);
                addTotalBytes(totalBytes, size);
                filePaths.add(normalized);
            }
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Invalid ZIP archive: " + safeMessage(exception), exception);
        }

        checkCancellation(cancellationRequested);
        return createDescriptor(source, ShaderPackSourceType.ZIP_ARCHIVE, filePaths, totalBytes.get(), cancellationRequested);
    }

    private ShaderPackDescriptor createDescriptor(
            Path source,
            ShaderPackSourceType sourceType,
            Set<String> filePaths,
            long totalBytes,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Set<String> shaderRoots = new HashSet<>();
        int shaderFileCount = 0;
        int pipelineEntryPointCount = 0;

        for (String filePath : filePaths) {
            checkCancellation(cancellationRequested);
            String[] segments = filePath.split("/");
            for (int index = 0; index < segments.length - 1; index++) {
                if (!segments[index].equalsIgnoreCase("shaders")) {
                    continue;
                }

                String rootPrefix = String.join("/", java.util.Arrays.copyOfRange(segments, 0, index));
                if (rootPrefix.contains("/")) {
                    break;
                }
                shaderRoots.add(rootPrefix);

                String fileName = segments[segments.length - 1];
                if (SHADER_FILE.matcher(fileName).matches()) {
                    shaderFileCount++;
                    if (PIPELINE_ENTRY_POINT.matcher(fileName).matches()) {
                        pipelineEntryPointCount++;
                    }
                }
                break;
            }
        }

        if (shaderRoots.isEmpty()) {
            throw new IOException("No shaders/ directory was found.");
        }
        if (shaderRoots.size() > 1) {
            throw new IOException("Multiple shaders/ roots were found; the pack layout is ambiguous.");
        }
        if (pipelineEntryPointCount == 0) {
            throw new IOException("No recognized shader pipeline entry points were found under shaders/.");
        }
        String shaderRoot = shaderRoots.iterator().next();

        String displayName = source.getFileName().toString();
        if (sourceType == ShaderPackSourceType.ZIP_ARCHIVE && displayName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        if (displayName.isBlank()) {
            displayName = "Shader pack";
        }

        return new ShaderPackDescriptor(
                createId(displayName),
                displayName,
                source,
                shaderRoot,
                sourceType,
                ShaderPackLayout.OPTIFINE_STYLE,
                shaderFileCount,
                pipelineEntryPointCount,
                totalBytes
        );
    }

    private String normalizeArchivePath(String path) throws IOException {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            throw new IOException("ZIP archive contains an empty or invalid entry path.");
        }
        if (path.startsWith("/") || path.matches("(?i)^[a-z]:.*")) {
            throw new IOException("ZIP archive contains an absolute entry path: " + path);
        }

        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("ZIP archive contains an unsafe entry path: " + path);
            }
        }
        return normalized;
    }

    private String normalizeRelativePath(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Directory pack contains an invalid relative path.");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Directory pack contains an unsafe relative path: " + path);
            }
        }
        return normalized;
    }

    private void validateEntrySize(long size, String entryName) throws IOException {
        if (size < 0) {
            throw new IOException("Entry has an unknown or invalid uncompressed size: " + entryName);
        }
        if (size > MAX_SINGLE_ENTRY_BYTES) {
            throw new IOException("Entry exceeds the uncompressed size limit: " + entryName);
        }
    }

    private void validateCompressionRatio(long size, long compressedSize, String entryName) throws IOException {
        if (size < MIN_RATIO_CHECK_BYTES) {
            return;
        }
        if (compressedSize < 0) {
            throw new IOException("ZIP entry has an unknown compressed size: " + entryName);
        }
        double ratio = compressedSize == 0 ? Double.POSITIVE_INFINITY : (double) size / compressedSize;
        if (ratio > MAX_COMPRESSION_RATIO) {
            throw new IOException("ZIP entry has a suspicious compression ratio: " + entryName);
        }
    }

    private void addTotalBytes(AtomicLong totalBytes, long size) throws IOException {
        long total;
        try {
            total = Math.addExact(totalBytes.get(), size);
        } catch (ArithmeticException exception) {
            throw new IOException("Pack size overflowed the supported range.", exception);
        }
        if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            throw new IOException("Pack exceeds the total uncompressed size limit of " + MAX_TOTAL_UNCOMPRESSED_BYTES + " bytes.");
        }
        totalBytes.set(total);
    }

    private void countEntry(AtomicLong entryCount) throws IOException {
        if (entryCount.incrementAndGet() > MAX_ENTRIES) {
            throw new IOException("Directory pack contains more than " + MAX_ENTRIES + " entries.");
        }
    }

    private static void checkCancellation(BooleanSupplier cancellationRequested) {
        if (cancellationRequested.getAsBoolean()) {
            throw new CancellationException("Shader-pack scan was cancelled.");
        }
    }

    private String createId(String displayName) {
        String id = displayName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        String stableSuffix = Integer.toUnsignedString(displayName.hashCode(), 36);
        return (id.isBlank() ? "shader-pack" : id) + "-" + stableSuffix;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
