package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses OptiFine-style option metadata and GLSL inline option declarations. */
public record ShaderPackMetadata(Map<String, String> properties, List<Option> options) {
    private static final int MAX_FILE_CHARS = 256 * 1024;
    private static final int MAX_LINES = 8_192;
    private static final int MAX_SCREEN_DEPTH = 16;
    private static final int MAX_SHADER_FILES = 512;
    private static final int MAX_TOTAL_SHADER_CHARS = 8 * 1024 * 1024;
    private static final Pattern OPTION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern INLINE_DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\s/]+)\\s*//\\s*\\[([^\\]]+)](?:\\s*.*)?$"
    );

    public record Option(String name, String label, String defaultValue, List<String> values, boolean slider) {
        public Option { values = List.copyOf(values); }
    }

    private record SourceOption(String defaultValue, List<String> values) { }

    public ShaderPackMetadata {
        properties = Map.copyOf(properties);
        options = List.copyOf(options);
    }

    public static ShaderPackMetadata parse(String source) {
        return parse(source, Map.of());
    }

    /** Parses option declarations supplied as shader-relative source text. */
    public static ShaderPackMetadata parse(String source, Map<String, String> shaderSources) {
        if (source == null || source.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("shaders.properties is missing or exceeds the metadata size limit.");
        }
        Map<String, String> values = parseProperties(source);
        Map<String, SourceOption> sourceOptions = parseInlineOptions(shaderSources);

        Set<String> visible = new LinkedHashSet<>();
        Set<String> visitedScreens = new LinkedHashSet<>();
        expandScreen("screen", values, visible, visitedScreens, 0);
        for (String key : values.keySet()) {
            if (key.startsWith("screen.")) expandScreen(key, values, visible, visitedScreens, 0);
        }
        Set<String> sliders = new LinkedHashSet<>(tokens(values.getOrDefault("sliders", "")));

        Map<String, Option> discovered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().startsWith("option.")) continue;
            String name = entry.getKey().substring("option.".length());
            if (!OPTION_NAME.matcher(name).matches()) continue;
            List<String> choices = tokens(entry.getValue());
            if (choices.isEmpty()) continue;
            SourceOption inline = sourceOptions.get(name);
            String defaultValue = inline == null ? choices.getFirst() : inline.defaultValue();
            if (!choices.contains(defaultValue)) {
                List<String> expanded = new ArrayList<>();
                expanded.add(defaultValue);
                expanded.addAll(choices);
                choices = expanded;
            }
            discovered.put(name, new Option(name, humanize(name), defaultValue,
                    choices, sliders.contains(name)));
        }
        sourceOptions.forEach((name, inline) -> {
            if (discovered.containsKey(name)) return;
            if (!OPTION_NAME.matcher(name).matches()) return;
            if (!visible.isEmpty() && !visible.contains(name)) return;
            discovered.put(name, new Option(name, humanize(name), inline.defaultValue(),
                    inline.values(), sliders.contains(name)));
        });

        List<Option> options = new ArrayList<>();
        if (visible.isEmpty()) {
            options.addAll(discovered.values());
        } else {
            for (String name : visible) {
                Option option = discovered.get(name);
                if (option != null) options.add(option);
            }
        }
        return new ShaderPackMetadata(values, options);
    }

    /** Reads only bounded shader source files, without extracting archives. */
    public static ShaderPackMetadata parse(String properties, ShaderPackSources sources) throws IOException {
        Map<String, String> shaderSources = new LinkedHashMap<>();
        int totalChars = 0;
        for (String path : sources.listShaderFiles()) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".gsh")
                    || lower.endsWith(".csh") || lower.endsWith(".tcs") || lower.endsWith(".tes")
                    || lower.endsWith(".glsl"))) continue;
            if (shaderSources.size() >= MAX_SHADER_FILES) {
                throw new IOException("Shader pack exceeds the option metadata scan limit of "
                        + MAX_SHADER_FILES + " shader files.");
            }
            String content = sources.read(path);
            totalChars = Math.addExact(totalChars, content.length());
            if (totalChars > MAX_TOTAL_SHADER_CHARS) {
                throw new IOException("Shader pack exceeds the option metadata scan size limit.");
            }
            shaderSources.put(path, content);
        }
        return parse(properties, shaderSources);
    }

    private static Map<String, String> parseProperties(String source) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = source.split("\\R", -1);
        if (lines.length > MAX_LINES) throw new IllegalArgumentException("shaders.properties contains too many lines.");
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int separator = firstSeparator(line);
            if (separator <= 0) continue;
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (key.length() <= 256 && value.length() <= 4_096) values.put(key, value);
        }
        return values;
    }

    private static Map<String, SourceOption> parseInlineOptions(Map<String, String> sources) {
        Map<String, SourceOption> options = new LinkedHashMap<>();
        if (sources == null) return options;
        for (String source : sources.values()) {
            if (source == null) continue;
            for (String line : source.split("\\R", -1)) {
                Matcher matcher = INLINE_DEFINE.matcher(line);
                if (!matcher.matches()) continue;
                String name = matcher.group(1);
                String defaultValue = matcher.group(2);
                if (!OPTION_NAME.matcher(name).matches() || !safeValue(defaultValue)) continue;
                List<String> choices = tokens(matcher.group(3));
                if (choices.isEmpty() || choices.size() > 64) continue;
                if (!choices.contains(defaultValue)) {
                    List<String> expanded = new ArrayList<>();
                    expanded.add(defaultValue);
                    expanded.addAll(choices);
                    choices = expanded;
                }
                options.putIfAbsent(name, new SourceOption(defaultValue, choices));
            }
        }
        return options;
    }

    private static boolean safeValue(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9_+./-]+");
    }

    private static void expandScreen(
            String screenKey,
            Map<String, String> properties,
            Set<String> orderedOptions,
            Set<String> visited,
            int depth
    ) {
        if (depth >= MAX_SCREEN_DEPTH || !visited.add(screenKey)) return;
        for (String token : tokens(properties.getOrDefault(screenKey, ""))) {
            String nestedKey = "screen." + token;
            if (properties.containsKey(nestedKey)) {
                expandScreen(nestedKey, properties, orderedOptions, visited, depth + 1);
            } else {
                orderedOptions.add(token);
            }
        }
    }

    private static int firstSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) return colon;
        if (colon < 0) return equals;
        return Math.min(equals, colon);
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.split("[\\s,]+")) {
            String cleaned = token.strip().replace("\"", "");
            if (!cleaned.isEmpty() && cleaned.length() <= 128
                    && cleaned.matches("[A-Za-z0-9_+./-]+")) result.add(cleaned);
        }
        return result;
    }

    private static String humanize(String name) {
        String spaced = name.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
