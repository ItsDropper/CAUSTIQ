package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses OptiFine/Iris block.properties IDs for mc_Entity terrain metadata. */
public final class IrisBlockPropertiesParser {
    private static final Pattern ENTRY = Pattern.compile("^block\\.(-?\\d+)\\s*=\\s*(.*)$");
    private static final Pattern VERSION_CONDITION = Pattern.compile(
            "^#\\s*if\\s+MC_VERSION\\s*(>=|>|<=|<|==|!=)\\s*(\\d+)\\s*$");

    /**
     * Parses using the project's current Minecraft target. Kept for callers and
     * tests that exercise the pinned 1.21.11 runtime.
     */
    public Map<String, Integer> parse(String source) {
        return parse(source, 12_111);
    }

    /** Parses only the block.properties preprocessor branch active for MC_VERSION. */
    public Map<String, Integer> parse(String source, int mcVersion) {
        if (source == null || source.isBlank()) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        var activeLines = activeLines(source, mcVersion);
        for (int lineNumber = 0; lineNumber < activeLines.size(); lineNumber++) {
            String line = stripComment(activeLines.get(lineNumber)).trim();
            if (line.isEmpty()) continue;
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) continue;
            int id;
            try {
                id = Integer.parseInt(entry.group(1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid block.properties ID on line " + (lineNumber + 1), exception);
            }
            if (id < 0 || id > 0x3FFF_FFFE) {
                throw new IllegalArgumentException("Block ID out of packed mc_Entity range on line " + (lineNumber + 1));
            }
            String[] values = entry.group(2).trim().split("[\\s,]+");
            for (String value : values) {
                if (value.isBlank()) continue;
                if (value.startsWith("%") || value.startsWith("tags_")) continue;
                ParsedBlock block = parseBlock(value, lineNumber + 1);
                String key = block.properties().isEmpty()
                        ? block.identifier()
                        : block.identifier() + formatProperties(block.properties());
                putFirst(result, key, id);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> activeLines(String source, int mcVersion) {
        // Iris exposes block tag selectors only when its tag support API is available.
        // CAUSTIQ currently resolves concrete block identifiers, so select the non-tag
        // branch and deliberately ignore tag aliases instead of treating them as blocks.
        String preprocessed = new IrisGlslConditionalPreprocessor().preprocess(
                source, Map.of("MC_VERSION", Integer.toString(mcVersion), "IRIS_TAG_SUPPORT", "0"));
        return new ArrayList<>(List.of(preprocessed.split("\\R", -1)));
    }

    private static ParsedBlock parseBlock(String token, int lineNumber) {
        String value = token.trim();
        int propertyStart = value.indexOf('[');
        String propertyText = null;
        if (propertyStart >= 0) {
            if (!value.endsWith("]")) {
                throw new IllegalArgumentException("Malformed block state selector on line " + lineNumber + ": " + token);
            }
            propertyText = value.substring(propertyStart + 1, value.length() - 1);
            value = value.substring(0, propertyStart);
        } else {
            int firstSeparator = value.indexOf(':');
            if (firstSeparator >= 0) {
                String suffix = value.substring(firstSeparator + 1);
                boolean unqualifiedState = suffix.matches("[a-zA-Z0-9_]+=[^:]+(?::.*)?");
                int selectorSeparator = unqualifiedState
                        ? firstSeparator : value.indexOf(':', firstSeparator + 1);
                if (selectorSeparator >= 0) {
                    propertyText = value.substring(selectorSeparator + 1);
                    value = value.substring(0, selectorSeparator);
                }
            }
        }
        if (!value.contains(":")) value = "minecraft:" + value;
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid block identifier on line " + lineNumber + ": " + token);
        }
        Map<String, String> properties = new TreeMap<>();
        if (propertyText != null && !propertyText.isBlank()) {
            for (String assignment : propertyText.split("[,;:]")) {
                String[] pair = assignment.trim().split("=", 2);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                    throw new IllegalArgumentException("Invalid block state property on line " + lineNumber + ": " + token);
                }
                properties.put(pair[0].trim(), pair[1].trim());
            }
        }
        return new ParsedBlock(value, properties);
    }

    private static String formatProperties(Map<String, String> properties) {
        StringBuilder result = new StringBuilder("[");
        properties.forEach((name, value) -> {
            if (result.length() > 1) result.append(',');
            result.append(name).append('=').append(value);
        });
        return result.append(']').toString();
    }

    /** Iris/OptiFine block-state maps use first-match precedence for duplicate selectors. */
    private static void putFirst(Map<String, Integer> target, String key, int id) {
        target.putIfAbsent(key, id);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int slash = line.indexOf("//");
        int end = hash < 0 ? slash : slash < 0 ? hash : Math.min(hash, slash);
        return end < 0 ? line : line.substring(0, end);
    }

    private record Conditional(boolean parentActive, boolean condition, boolean elseSeen, int line) { }
    private record ParsedBlock(String identifier, Map<String, String> properties) { }
}
