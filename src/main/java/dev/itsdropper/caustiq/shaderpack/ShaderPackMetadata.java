package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses the user-facing option declarations from OptiFine-style shaders.properties. */
public record ShaderPackMetadata(Map<String, String> properties, List<Option> options) {
    private static final int MAX_FILE_CHARS = 256 * 1024;
    private static final int MAX_LINES = 8_192;
    private static final int MAX_SCREEN_DEPTH = 16;
    private static final Pattern OPTION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public record Option(String name, String label, List<String> values, boolean slider) {
        public Option { values = List.copyOf(values); }
        public String defaultValue() { return values.getFirst(); }
    }

    public ShaderPackMetadata {
        properties = Map.copyOf(properties);
        options = List.copyOf(options);
    }

    public static ShaderPackMetadata parse(String source) {
        if (source == null || source.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("shaders.properties is missing or exceeds the metadata size limit.");
        }
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

        Set<String> visible = new LinkedHashSet<>();
        Set<String> visitedScreens = new LinkedHashSet<>();
        expandScreen("screen", values, visible, visitedScreens, 0);
        for (String key : values.keySet()) {
            if (key.startsWith("screen.")) expandScreen(key, values, visible, visitedScreens, 0);
        }
        Set<String> sliders = new LinkedHashSet<>(tokens(values.getOrDefault("sliders", "")));

        List<Option> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().startsWith("option.")) continue;
            String name = entry.getKey().substring("option.".length());
            if (!OPTION_NAME.matcher(name).matches()) continue;
            List<String> choices = tokens(entry.getValue());
            if (choices.isEmpty()) continue;
            if (choices.size() > 64) choices = choices.subList(0, 64);
            if (!visible.isEmpty() && !visible.contains(name)) continue;
            options.add(new Option(name, humanize(name), choices, sliders.contains(name)));
        }
        options.sort((left, right) ->
                Integer.compare(indexOf(visible, left.name()), indexOf(visible, right.name())));
        return new ShaderPackMetadata(values, options);
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
            if (properties.containsKey(nestedKey)) expandScreen(nestedKey, properties, orderedOptions, visited, depth + 1);
            else orderedOptions.add(token);
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

    private static int indexOf(Set<String> ordered, String value) {
        int index = 0;
        for (String candidate : ordered) {
            if (candidate.equals(value)) return index;
            index++;
        }
        return Integer.MAX_VALUE;
    }

    private static String humanize(String name) {
        String spaced = name.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
