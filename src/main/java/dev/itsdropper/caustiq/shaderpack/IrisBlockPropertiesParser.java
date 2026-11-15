package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayDeque;
import java.util.Deque;
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
                ParsedBlock block = parseBlock(value, lineNumber + 1);
                String key = block.properties().isEmpty()
                        ? block.identifier()
                        : block.identifier() + formatProperties(block.properties());
                putUnique(result, key, id, lineNumber + 1);
            }
        }
        return Map.copyOf(result);
    }

    private static java.util.List<String> activeLines(String source, int mcVersion) {
        java.util.List<String> result = new java.util.ArrayList<>();
        Deque<Conditional> stack = new ArrayDeque<>();
        boolean active = true;
        String[] lines = source.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#if")) {
                Matcher condition = VERSION_CONDITION.matcher(trimmed);
                if (!condition.matches()) {
                    throw new IllegalArgumentException("Unsupported block.properties conditional on line "
                            + (i + 1) + ": " + trimmed);
                }
                boolean matches = evaluateVersionCondition(
                        mcVersion, condition.group(1), Integer.parseInt(condition.group(2)));
                stack.push(new Conditional(active, matches, false, i + 1));
                active = active && matches;
            } else if (trimmed.equals("#else")) {
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected #else in block.properties on line " + (i + 1));
                }
                Conditional previous = stack.pop();
                if (previous.elseSeen()) {
                    throw new IllegalArgumentException("Duplicate #else in block.properties on line " + (i + 1));
                }
                stack.push(new Conditional(previous.parentActive(), previous.condition(), true, previous.line()));
                active = previous.parentActive() && !previous.condition();
            } else if (trimmed.equals("#endif")) {
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected #endif in block.properties on line " + (i + 1));
                }
                active = stack.pop().parentActive();
            } else if (active) {
                result.add(lines[i]);
            }
        }
        if (!stack.isEmpty()) {
            Conditional unclosed = stack.peek();
            throw new IllegalArgumentException("Unclosed #if in block.properties on line " + unclosed.line());
        }
        return result;
    }

    private static boolean evaluateVersionCondition(int version, String operator, int threshold) {
        return switch (operator) {
            case ">=" -> version >= threshold;
            case ">" -> version > threshold;
            case "<=" -> version <= threshold;
            case "<" -> version < threshold;
            case "==" -> version == threshold;
            case "!=" -> version != threshold;
            default -> throw new IllegalArgumentException("Unsupported MC_VERSION operator: " + operator);
        };
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
            int selectorSeparator = namespaceSeparator < 0
                    ? -1 : value.indexOf(':', namespaceSeparator + 1);
            if (selectorSeparator < 0 && namespaceSeparator >= 0
                    && value.substring(namespaceSeparator + 1).contains("=")) {
                selectorSeparator = namespaceSeparator;
            }
            if (selectorSeparator >= 0) {
                propertyText = value.substring(selectorSeparator + 1);
                value = value.substring(0, selectorSeparator);
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

    private record Conditional(boolean parentActive, boolean condition, boolean elseSeen, int line) { }
    private record ParsedBlock(String identifier, Map<String, String> properties) { }
}
