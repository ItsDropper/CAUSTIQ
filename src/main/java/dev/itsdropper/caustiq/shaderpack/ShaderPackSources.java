package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safe, bounded source access for discovered directory and ZIP shader packs.
 * The returned instance must be closed when preprocessing is complete.
 */
public final class ShaderPackSources implements GlslPreprocessor.SourceProvider, AutoCloseable {
    private final Path directoryRoot;
    private final ZipFile archive;
    private final String archivePrefix;

    private ShaderPackSources(Path directoryRoot, ZipFile archive, String archivePrefix) {
        this.directoryRoot = directoryRoot;
        this.archive = archive;
        this.archivePrefix = archivePrefix;
    }

    public static ShaderPackSources open(ShaderPackDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        Path packRoot = descriptor.source().toAbsolutePath().normalize();
        String wrapper = descriptor.shaderRoot().isEmpty() ? "" : descriptor.shaderRoot() + "/";
        if (descriptor.sourceType() == ShaderPackSourceType.DIRECTORY) {
            Path shaderRoot = packRoot.resolve(wrapper).resolve("shaders").normalize();
            if (!shaderRoot.startsWith(packRoot) || Files.isSymbolicLink(packRoot)) {
                throw new IOException("Shader source directory is missing or unsafe: " + shaderRoot);
            }
            Path current = packRoot;
            for (Path segment : packRoot.relativize(shaderRoot)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("Symbolic links are not permitted in shader source roots: " + current);
                }
            }
            if (!Files.isDirectory(shaderRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Shader source directory is missing or unsafe: " + shaderRoot);
            }
            return new ShaderPackSources(shaderRoot, null, null);
        }

        if (Files.isSymbolicLink(packRoot) || !Files.isRegularFile(packRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader archive is missing or unsafe: " + packRoot);
        }
        ZipFile zip = new ZipFile(packRoot.toFile());
        return new ShaderPackSources(null, zip, wrapper + "shaders/");
    }

    @Override
    public String read(String normalizedPath) throws IOException {
        String safePath = validateRelativePath(normalizedPath);
        if (archive != null) {
            return readArchiveEntry(safePath);
        }
        return readDirectoryFile(safePath);
    }

    private String readDirectoryFile(String relativePath) throws IOException {
        Path file = directoryRoot.resolve(relativePath).normalize();
        if (!file.startsWith(directoryRoot)) {
            throw new IOException("Shader source path escapes the pack root: " + relativePath);
        }
        Path current = directoryRoot;
        for (Path segment : directoryRoot.relativize(file)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not permitted in shader source paths: " + relativePath);
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader source does not exist: " + relativePath);
        }
        long size = Files.size(file);
        if (size > GlslPreprocessor.MAX_SOURCE_BYTES) {
            throw new IOException("Shader source exceeds the " + GlslPreprocessor.MAX_SOURCE_BYTES + " byte limit: " + relativePath);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return decode(readBounded(input, relativePath), relativePath);
        }
    }

    private String readArchiveEntry(String relativePath) throws IOException {
        ZipEntry entry = archive.getEntry(archivePrefix + relativePath);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Shader source does not exist in archive: " + relativePath);
        }
        if (entry.getSize() < 0 || entry.getSize() > GlslPreprocessor.MAX_SOURCE_BYTES) {
            throw new IOException("Shader archive entry has an invalid or excessive size: " + relativePath);
        }
        try (InputStream input = archive.getInputStream(entry)) {
            return decode(readBounded(input, relativePath), relativePath);
        }
    }

    private byte[] readBounded(InputStream input, String path) throws IOException {
        byte[] bytes = input.readNBytes(GlslPreprocessor.MAX_SOURCE_BYTES + 1);
        if (bytes.length > GlslPreprocessor.MAX_SOURCE_BYTES) {
            throw new IOException("Shader source exceeds the " + GlslPreprocessor.MAX_SOURCE_BYTES + " byte limit: " + path);
        }
        return bytes;
    }

    private String decode(byte[] bytes, String path) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Shader source is not valid UTF-8: " + path, exception);
        }
    }

    private String validateRelativePath(String path) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) {
            throw new IOException("Invalid shader source path.");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Unsafe shader source path: " + path);
            }
        }
        return path;
    }

    @Override
    public void close() throws IOException {
        if (archive != null) {
            archive.close();
        }
    }
}
