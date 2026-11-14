package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the conditional subset used by Iris shader stages after include
 * expansion. This prevents inactive program variants (entities, voxels, DH,
 * optional POM and optional material maps) from being mistaken for active
 * terrain requirements.
 */
public final class IrisGlslConditionalPreprocessor {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*([A-Za-z_][A-Za-z0-9_]*)(.*)$");
    private static final Pattern DEFINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*))?$");
    private static final Pattern FUNCTION_DEFINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(.*$");
    private static final Pattern TOKEN = Pattern.compile(
            "defined|&&|\\|\\||==|!=|<=|>=|<<|>>|0[xX][0-9A-Fa-f]+|[0-9]+(?:\\.[0-9]+)?|[A-Za-z_][A-Za-z0-9_]*|[()!~<>+*/%&|^\\-]");

    public String preprocess(String source, Map<String, String> initialDefines) {
        Objects.requireNonNull(source, "source");
        Map<String, String> macros = new LinkedHashMap<>(initialDefines == null ? Map.of() : initialDefines);
        String normalized = source.replace("\\" + "\r\n", " ")
                .replace("\\" + "\n", " ")
                .replace("\\" + "\r", " ");
        String[] lines = normalized.split("\\R", -1);
        StringBuilder output = new StringBuilder(source.length());
        Deque<Conditional> stack = new ArrayDeque<>();
        boolean active = true;

        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            Matcher directive = DIRECTIVE.matcher(line);
            if (!directive.matches()) {
                if (active) output.append(line).append('\\n');
                continue;
            }

            String command = directive.group(1).toLowerCase();
            String argument = directive.group(2).trim();
            switch (command) {
                case "if", "ifdef", "ifndef" -> {
                    boolean parentActive = active;
                    boolean condition = parentActive && evaluateCondition(command, argument, macros, lineNumber + 1);
                    Conditional frame = new Conditional(parentActive, condition);
                    stack.push(frame);
                    active = frame.active;
                }
                case "elif" -> {
                    Conditional frame = requireFrame(stack, command, lineNumber + 1);
                    if (frame.elseSeen) throw malformed(command, lineNumber + 1);
                    boolean condition = frame.parentActive && !frame.branchTaken
                            && evaluateCondition("if", argument, macros, lineNumber + 1);
                    frame.active = condition;
                    frame.branchTaken |= condition;
                    active = frame.active;
                }
                case "else" -> {
                    Conditional frame = requireFrame(stack, command, lineNumber + 1);
                    if (frame.elseSeen) throw malformed(command, lineNumber + 1);
                    frame.elseSeen = true;
                    frame.active = frame.parentActive && !frame.branchTaken;
                    frame.branchTaken = true;
                    active = frame.active;
                }
                case "endif" -> {
                    Conditional frame = requireFrame(stack, command, lineNumber + 1);
                    stack.pop();
                    active = frame.parentActive;
                }
                case "define" -> {
                    if (active) {
                        define(argument, macros);
                        output.append(line).append('\\n');
                    }
                }
                case "undef" -> {
                    if (active) {
                        macros.remove(firstIdentifier(argument));
                        output.append(line).append('\\n');
                    }
                }
                default -> {
                    if (active) output.append(line).append('\\n');
                }
            }
        }

        if (!stack.isEmpty()) {
            throw new IllegalArgumentException("Unclosed GLSL conditional block in translated terrain stage.");
        }
        return output.toString();
    }

    private static boolean evaluateCondition(
            String command, String expression, Map<String, String> macros, int lineNumber) {
        if (command.equals("ifdef")) return macros.containsKey(firstIdentifier(expression));
        if (command.equals("ifndef")) return !macros.containsKey(firstIdentifier(expression));
        String clean = expression.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", " ").trim();
        try {
            return new Expression(clean, macros).evaluate() != 0L;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported GLSL conditional at line " + lineNumber
                    + ": " + expression, exception);
        }
    }

    private static void define(String argument, Map<String, String> macros) {
        Matcher matcher = DEFINE.matcher(argument);
        if (matcher.matches()) {
            String value = matcher.group(2);
            macros.put(matcher.group(1), value == null || value.isBlank() ? "1" : value.trim());
            return;
        }
        Matcher function = FUNCTION_DEFINE.matcher(argument);
        if (function.matches()) macros.put(function.group(1), "1");
    }

    private static String firstIdentifier(String argument) {
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(argument);
        if (!matcher.find()) throw new IllegalArgumentException("Expected a GLSL macro identifier: " + argument);
        return matcher.group();
    }

    private static Conditional requireFrame(Deque<Conditional> stack, String command, int lineNumber) {
        if (stack.isEmpty()) throw malformed(command, lineNumber);
        return stack.peek();
    }

    private static IllegalArgumentException malformed(String command, int lineNumber) {
        return new IllegalArgumentException("Malformed #" + command + " at GLSL line " + lineNumber + ".");
    }

    private static final class Conditional {
        private final boolean parentActive;
        private boolean branchTaken;
        private boolean active;
        private boolean elseSeen;

        private Conditional(boolean parentActive, boolean condition) {
            this.parentActive = parentActive;
            this.branchTaken = condition;
            this.active = parentActive && condition;
        }
    }

    private static final class Expression {
        private final List<String> tokens = new ArrayList<>();
        private final Map<String, String> macros;
        private int cursor;

        private Expression(String source, Map<String, String> macros) {
            this.macros = macros;
            Matcher matcher = TOKEN.matcher(source);
            int offset = 0;
            while (matcher.find()) {
                if (!source.substring(offset, matcher.start()).isBlank()) {
                    throw new IllegalArgumentException("Invalid token in conditional expression.");
                }
                tokens.add(matcher.group());
                offset = matcher.end();
            }
            if (!source.substring(offset).isBlank()) {
                throw new IllegalArgumentException("Invalid trailing token in conditional expression.");
            }
        }

        private long evaluate() {
            long value = parseLogicalOr();
            if (cursor != tokens.size()) throw new IllegalArgumentException("Unexpected token " + peek());
            return value;
        }

        private long parseLogicalOr() {
            long value = parseLogicalAnd();
            while (match("||")) {
                long right = parseLogicalAnd();
                value = truth(value) || truth(right) ? 1 : 0;
            }
            return value;
        }

        private long parseLogicalAnd() {
            long value = parseBitwiseOr();
            while (match("&&")) {
                long right = parseBitwiseOr();
                value = truth(value) && truth(right) ? 1 : 0;
            }
            return value;
        }

        private long parseBitwiseOr() {
            long value = parseBitwiseXor();
            while (match("|")) value |= parseBitwiseXor();
            return value;
        }

        private long parseBitwiseXor() {
            long value = parseBitwiseAnd();
            while (match("^")) value ^= parseBitwiseAnd();
            return value;
        }

        private long parseBitwiseAnd() {
            long value = parseEquality();
            while (match("&")) value &= parseEquality();
            return value;
        }

        private long parseEquality() {
            long value = parseRelational();
            while (true) {
                if (match("==")) value = value == parseRelational() ? 1 : 0;
                else if (match("!=")) value = value != parseRelational() ? 1 : 0;
                else return value;
            }
        }

        private long parseRelational() {
            long value = parseShift();
            while (true) {
                if (match("<")) value = value < parseShift() ? 1 : 0;
                else if (match("<=")) value = value <= parseShift() ? 1 : 0;
                else if (match(">")) value = value > parseShift() ? 1 : 0;
                else if (match(">=")) value = value >= parseShift() ? 1 : 0;
                else return value;
            }
        }

        private long parseShift() {
            long value = parseAdditive();
            while (true) {
                if (match("<<")) value <<= parseAdditive();
                else if (match(">>")) value >>= parseAdditive();
                else return value;
            }
        }

        private long parseAdditive() {
            long value = parseMultiplicative();
            while (true) {
                if (match("+")) value += parseMultiplicative();
                else if (match("-")) value -= parseMultiplicative();
                else return value;
            }
        }

        private long parseMultiplicative() {
            long value = parseUnary();
            while (true) {
                if (match("*")) value *= parseUnary();
                else if (match("/")) {
                    long divisor = parseUnary();
                    value = divisor == 0 ? 0 : value / divisor;
                } else if (match("%")) {
                    long divisor = parseUnary();
                    value = divisor == 0 ? 0 : value % divisor;
                } else return value;
            }
        }

        private long parseUnary() {
            if (match("!")) return truth(parseUnary()) ? 0 : 1;
            if (match("~")) return ~parseUnary();
            if (match("+")) return parseUnary();
            if (match("-")) return -parseUnary();
            return parsePrimary();
        }

        private long parsePrimary() {
            if (match("(")) {
                long value = parseLogicalOr();
                expect(")");
                return value;
            }
            if (match("defined")) {
                boolean parenthesized = match("(");
                String name = takeIdentifier();
                if (parenthesized) expect(")");
                return macros.containsKey(name) ? 1 : 0;
            }
            String token = take();
            if (token.startsWith("0x") || token.startsWith("0X")) {
                return Long.parseUnsignedLong(token.substring(2), 16);
            }
            if (Character.isDigit(token.charAt(0))) {
                return (long) Double.parseDouble(token);
            }
            return macroValue(token, new java.util.HashSet<>());
        }

        private long macroValue(String name, java.util.Set<String> resolving) {
            String value = macros.get(name);
            if (value == null) return 0;
            if (!resolving.add(name)) return 0;
            String clean = value.trim().replaceAll("[uUlLfF]+$", "");
            if (clean.isBlank()) return 1;
            try {
                return Long.parseLong(clean);
            } catch (NumberFormatException ignored) {
                try {
                    return (long) Double.parseDouble(clean);
                } catch (NumberFormatException ignoredAgain) {
                    if (clean.matches("[A-Za-z_][A-Za-z0-9_]*")) return macroValue(clean, resolving);
                    try {
                        return new Expression(clean, macros).evaluate();
                    } catch (RuntimeException ignoredExpression) {
                        return 0;
                    }
                }
            }
        }

        private String takeIdentifier() {
            String token = take();
            if (!token.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Expected macro identifier, got " + token);
            }
            return token;
        }

        private String take() {
            if (cursor >= tokens.size()) throw new IllegalArgumentException("Unexpected end of expression.");
            return tokens.get(cursor++);
        }

        private String peek() { return cursor < tokens.size() ? tokens.get(cursor) : "<end>"; }

        private void expect(String expected) {
            if (!match(expected)) throw new IllegalArgumentException("Expected " + expected + ", got " + peek());
        }

        private boolean match(String token) {
            if (cursor < tokens.size() && tokens.get(cursor).equals(token)) {
                cursor++;
                return true;
            }
            return false;
        }

        private static boolean truth(long value) { return value != 0L; }
    }
}
