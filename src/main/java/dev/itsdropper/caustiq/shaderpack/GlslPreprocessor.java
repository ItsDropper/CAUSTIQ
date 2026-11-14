package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded GLSL include expansion. Macro expansion and GLSL validation remain compiler responsibilities. */
public final class GlslPreprocessor {
    public static final int MAX_INCLUDE_DEPTH = 32;
    public static final int MAX_SOURCE_FILES = 256;
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TOTAL_SOURCE_BYTES = 32 * 1024 * 1024;

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[\"<]([^\">]+)[\">]\\s*(?://.*)?$");
    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+([0-9]+)(?:\\s+([A-Za-z]+))?\\s*(?://.*)?$");
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\s+([A-Za-z0-9_]+)\\s*:\\s*(require|enable|warn|disable)\\s*(?://.*)?$");
    private static final Pattern PRAGMA_ONCE = Pattern.compile("^\\s*#\\s*pragma\\s+once\\s*(?://.*)?$");

    /** Reads UTF-8 source using a normalized path relative to the pack's shaders/ directory. */
    @FunctionalInterface
    public interface SourceProvider {
        String read(String normalizedPath) throws IOException;
    }

    public record SourceLocation(int sourceId, String path) { }
    public record Extension(String name, String behavior) { }

    public record Result(
            String source,
            String versionDirective,
            List<Extension> extensions,
            List<SourceLocation> sourceMap,
            List<String> includedFiles
    ) {
        public Result {
            Objects.requireNonNull(source, "source");
            extensions = List.copyOf(extensions);
            sourceMap = List.copyOf(sourceMap);
            includedFiles = List.copyOf(includedFiles);
        }
    }

    public Result preprocess(String entryPoint, SourceProvider provider) throws IOException {
        Objects.requireNonNull(provider, "provider");
        State state = new State(provider);
        String expanded = state.expand(normalizePath(entryPoint), 0, true);
        StringBuilder output = new StringBuilder(expanded.length() + 128);
        if (state.versionDirective != null) {
            output.append(state.versionDirective).append('\n');
        }
        output.append(expanded);
        return new Result(output.toString(), state.versionDirective,
                List.copyOf(state.extensions.values()), state.sourceMap, state.includedFiles);
    }

    private static final class State {
        private final SourceProvider provider;
        private final Deque<String> includeStack = new ArrayDeque<>();
        private final Set<String> onceFiles = new LinkedHashSet<>();
        private final Map<String, Integer> sourceIds = new LinkedHashMap<>();
        private final Map<String, String> sourceCache = new LinkedHashMap<>();
        private final Map<String, Extension> extensions = new LinkedHashMap<>();
        private final List<SourceLocation> sourceMap = new ArrayList<>();
        private final List<String> includedFiles = new ArrayList<>();
        private int totalBytes;
        private String versionDirective;

        private State(SourceProvider provider) {
            this.provider = provider;
        }

        private String expand(String path, int depth, boolean root) throws IOException {
            if (depth > MAX_INCLUDE_DEPTH) {
                throw failure(path, "maximum include depth of " + MAX_INCLUDE_DEPTH + " exceeded");
            }
            if (onceFiles.contains(path)) {
                return "";
            }
            if (includeStack.contains(path)) {
                List<String> cycle = new ArrayList<>(includeStack);
                cycle.add(path);
                throw failure(path, "include cycle detected: " + String.join(" -> ", cycle));
            }
            if (!sourceIds.containsKey(path) && sourceIds.size() >= MAX_SOURCE_FILES) {
                throw failure(path, "maximum source-file count of " + MAX_SOURCE_FILES + " exceeded");
            }

            String source = readSource(path);
            int bytes = source.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_SOURCE_BYTES) {
                throw failure(path, "source exceeds the " + MAX_SOURCE_BYTES + " byte limit");
            }
            try {
                totalBytes = Math.addExact(totalBytes, bytes);
            } catch (ArithmeticException exception) {
                throw failure(path, "total source size overflowed");
            }
            if (totalBytes > MAX_TOTAL_SOURCE_BYTES) {
                throw failure(path, "expanded source inputs exceed the " + MAX_TOTAL_SOURCE_BYTES + " byte limit");
            }

            int sourceId = sourceIds.computeIfAbsent(path, key -> {
                int id = sourceIds.size();
                sourceMap.add(new SourceLocation(id, key));
                return id;
            });
            includeStack.addLast(path);
            includedFiles.add(path);
            StringBuilder output = new StringBuilder(source.length() + 64);
            String[] lines = source.split("\\R", -1);
            try {
                for (int index = 0; index < lines.length; index++) {
                    String line = lines[index];
                    Matcher include = INCLUDE.matcher(line);
                    Matcher version = VERSION.matcher(line);
                    Matcher extension = EXTENSION.matcher(line);

                    if (PRAGMA_ONCE.matcher(line).matches()) {
                        // Mark immediately so a recursive include after this line is suppressed.
                        onceFiles.add(path);
                        continue;
                    }
                    if (version.matches()) {
                        if (!root) {
                            throw failure(path, "#version is not permitted in included source files");
                        }
                        if (versionDirective != null) {
                            throw failure(path, "multiple #version directives were found");
                        }
                        versionDirective = line.strip();
                        continue;
                    }
                    if (extension.matches()) {
                        String name = extension.group(1);
                        String behavior = extension.group(2);
                        Extension previous = extensions.putIfAbsent(name, new Extension(name, behavior));
                        if (previous != null && !previous.behavior().equals(behavior)) {
                            throw failure(path, "conflicting #extension behavior for " + name);
                        }
                    }
                    if (include.matches()) {
                        String target = resolveInclude(path, include.group(1), line.indexOf('<') >= 0);
                        String expanded = expand(target, depth + 1, false);
                        output.append("#line 1 ").append(sourceId(target)).append('\n');
                        output.append(expanded);
                        if (!expanded.isEmpty() && expanded.charAt(expanded.length() - 1) != '\n') {
                            output.append('\n');
                        }
                        output.append("#line ").append(index + 2).append(' ').append(sourceId).append('\n');
                    } else {
                        output.append(line).append('\n');
                    }
                }
            } finally {
                includeStack.removeLast();
            }
            return output.toString();
        }

        private String readSource(String path) throws IOException {
            String cached = sourceCache.get(path);
            if (cached != null) {
                return cached;
            }
            String source = provider.read(path);
            if (source == null) {
                throw failure(path, "source provider returned null");
            }
            int bytes = source.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_SOURCE_BYTES) {
                throw failure(path, "source exceeds the " + MAX_SOURCE_BYTES + " byte limit");
            }
            sourceCache.put(path, source);
            return source;
        }

        private String resolveInclude(String includingFile, String requested, boolean rooted) throws IOException {
            if (requested == null || requested.isBlank() || requested.indexOf('\\') >= 0) {
                throw failure(includingFile, "invalid include path");
            }
            if (requested.startsWith("/")) {
                rooted = true;
            }
            String candidate;
            if (rooted) {
                candidate = requested.startsWith("/") ? requested.substring(1) : requested;
            } else {
                int separator = includingFile.lastIndexOf('/');
                String parent = separator < 0 ? "" : includingFile.substring(0, separator + 1);
                candidate = parent + requested;
            }

            String normalized;
            try {
                normalized = normalizePath(candidate);
            } catch (IllegalArgumentException exception) {
                throw failure(includingFile, "include path escapes the shaders/ root: " + requested);
            }
            if (rooted) {
                return normalized;
            }
            try {
                readSource(normalized);
                return normalized;
            } catch (IOException relativeFailure) {
                try {
                    String packRootPath = normalizePath(requested);
                    readSource(packRootPath);
                    return packRootPath;
                } catch (IOException | IllegalArgumentException rootFailure) {
                    throw failure(includingFile, "unable to resolve include '" + requested + "'");
                }
            }
        }

        private int sourceId(String path) {
            Integer id = sourceIds.get(path);
            if (id == null) {
                throw new IllegalStateException("Source ID has not been assigned: " + path);
            }
            return id;
        }

        private IOException failure(String path, String message) {
            return new IOException(path + ": " + message);
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid shader source path");
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("Shader path escapes shaders root");
                }
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Shader source path is empty");
        }
        return String.join("/", segments);
    }
}
