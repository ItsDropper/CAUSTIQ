package dev.itsdropper.caustiq.shaderpack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses OptiFine/Iris block.properties IDs for mc_Entity terrain metadata. */
public final class IrisBlockPropertiesParser {
    private static final Pattern ENTRY = Pattern.compile("^block\\.(-?\\d+)\\s*=\\s*(.*)$");

    public Map<String, Integer> parse(String source) {
        if (source == null || source.isBlank()) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] lines = source.split("\\R");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = stripComment(lines[lineNumber]).trim();
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
                ParsedBlock block = parseBlock(value, lineNumber + 1);
                if (block.properties().isEmpty()) {
                    putUnique(result, block.identifier(), id, lineNumber + 1);
                } else {
                    putUnique(result, block.identifier() + formatProperties(block.properties()), id, lineNumber + 1);
                }
            }
        }
        return Map.copyOf(result);
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
            int namespaceSeparator = value.indexOf(':');
            int selectorSeparator = namespaceSeparator < 0 ? -1 : value.indexOf(':', namespaceSeparator + 1);
            if (selectorSeparator >= 0) {
                propertyText = value.substring(selectorSeparator + 1);
                value = value.substring(0, selectorSeparator);
            }
        }
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

    private static void putUnique(Map<String, Integer> target, String key, int id, int lineNumber) {
        Integer previous = target.putIfAbsent(key, id);
        if (previous != null && previous != id) {
            throw new IllegalArgumentException("Conflicting block.properties IDs for " + key
                    + " on line " + lineNumber + ": " + previous + " and " + id);
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int slash = line.indexOf("//");
        int end = hash < 0 ? slash : slash < 0 ? hash : Math.min(hash, slash);
        return end < 0 ? line : line.substring(0, end);
    }

    private record ParsedBlock(String identifier, Map<String, String> properties) { }
}
