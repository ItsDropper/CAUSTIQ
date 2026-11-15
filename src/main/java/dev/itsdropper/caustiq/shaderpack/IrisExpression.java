package dev.itsdropper.caustiq.shaderpack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Bounded scalar Iris expression evaluator used by custom uniforms and variables. */
public final class IrisExpression {
    private IrisExpression() { }

    public static double evaluate(String source, Function<String, Double> variables) {
        Parser parser = new Parser(source, variables);
        double value = parser.conditional();
        parser.whitespace();
        if (!parser.end()) throw parser.failure("Unexpected token");
        return value;
    }

    private static final class Parser {
        private final String source;
        private final Function<String, Double> variables;
        private int cursor;

        private Parser(String source, Function<String, Double> variables) {
            this.source = source == null ? "" : source;
            this.variables = variables == null ? ignored -> null : variables;
        }

        private double conditional() {
            double condition = or();
            whitespace();
            if (take("?")) {
                double whenTrue = conditional();
                require(":");
                double whenFalse = conditional();
                return truth(condition) ? whenTrue : whenFalse;
            }
            return condition;
        }

        private double or() {
            double value = and();
            while (take("||")) { double right = and(); value = truth(value) || truth(right) ? 1.0 : 0.0; }
            return value;
        }

        private double and() {
            double value = equality();
            while (take("&&")) { double right = equality(); value = truth(value) && truth(right) ? 1.0 : 0.0; }
            return value;
        }

        private double equality() {
            double value = comparison();
            while (true) {
                if (take("==")) value = value == comparison() ? 1.0 : 0.0;
                else if (take("!=")) value = value != comparison() ? 1.0 : 0.0;
                else return value;
            }
        }

        private double comparison() {
            double value = additive();
            while (true) {
                if (take(">=")) value = value >= additive() ? 1.0 : 0.0;
                else if (take("<=")) value = value <= additive() ? 1.0 : 0.0;
                else if (take(">")) value = value > additive() ? 1.0 : 0.0;
                else if (take("<")) value = value < additive() ? 1.0 : 0.0;
                else return value;
            }
        }

        private double additive() {
            double value = multiplicative();
            while (true) {
                if (take("+")) value += multiplicative();
                else if (take("-")) value -= multiplicative();
                else return value;
            }
        }

        private double multiplicative() {
            double value = unary();
            while (true) {
                if (take("*")) value *= unary();
                else if (take("/")) value /= unary();
                else if (take("%")) value %= unary();
                else return value;
            }
        }

        private double unary() {
            if (take("+")) return unary();
            if (take("-")) return -unary();
            if (take("!")) return truth(unary()) ? 0.0 : 1.0;
            return primary();
        }

        private double primary() {
            whitespace();
            if (take("(")) {
                double value = conditional();
                require(")");
                return value;
            }
            if (!end() && (Character.isDigit(source.charAt(cursor)) || source.charAt(cursor) == '.')) {
                return number();
            }
            String identifier = identifier();
            if (identifier.isEmpty()) throw failure("Expected a number, variable, or function");
            String normalized = identifier.toLowerCase(Locale.ROOT);
            if (normalized.equals("true")) return 1.0;
            if (normalized.equals("false")) return 0.0;
            if (normalized.equals("pi")) return Math.PI;
            if (normalized.equals("tau")) return Math.PI * 2.0;
            if (take("(")) {
                java.util.ArrayList<Double> args = new java.util.ArrayList<>();
                if (!take(")")) {
                    do { args.add(conditional()); } while (take(","));
                    require(")");
                }
                return function(normalized, args);
            }
            Double value = variables.apply(identifier);
            if (value == null) throw failure("Unknown Iris expression variable '" + identifier + "'");
            return value;
        }

        private double function(String name, java.util.List<Double> a) {
            return switch (name) {
                case "sin" -> unary(name, a, Math::sin);
                case "cos" -> unary(name, a, Math::cos);
                case "tan" -> unary(name, a, Math::tan);
                case "asin" -> unary(name, a, Math::asin);
                case "acos" -> unary(name, a, Math::acos);
                case "atan", "atan2" -> a.size() == 2 ? Math.atan2(a.get(0), a.get(1)) : unary(name, a, Math::atan);
                case "abs" -> unary(name, a, Math::abs);
                case "sign", "signum" -> unary(name, a, Math::signum);
                case "floor" -> unary(name, a, Math::floor);
                case "ceil" -> unary(name, a, Math::ceil);
                case "round" -> unary(name, a, v -> Math.floor(v + 0.5));
                case "fract", "frac" -> unary(name, a, v -> v - Math.floor(v));
                case "sqrt" -> unary(name, a, Math::sqrt);
                case "inversesqrt" -> unary(name, a, v -> 1.0 / Math.sqrt(v));
                case "exp" -> unary(name, a, Math::exp);
                case "log" -> unary(name, a, Math::log);
                case "torad", "radians" -> unary(name, a, v -> Math.toRadians(v));
                case "todeg", "degrees" -> unary(name, a, v -> Math.toDegrees(v));
                case "saturate" -> unary(name, a, v -> Math.max(0.0, Math.min(1.0, v)));
                case "pow" -> binary(name, a, Math::pow);
                case "mod", "fmod" -> binary(name, a, (x, y) -> x - y * Math.floor(x / y));
                case "min" -> aggregate(name, a, Math::min);
                case "max" -> aggregate(name, a, Math::max);
                case "clamp" -> { requireCount(name, a, 3); yield Math.max(a.get(1), Math.min(a.get(0), a.get(2))); }
                case "mix" -> { requireCount(name, a, 3); yield a.get(0) * (1.0 - a.get(2)) + a.get(1) * a.get(2); }
                case "step" -> { requireCount(name, a, 2); yield a.get(1) < a.get(0) ? 0.0 : 1.0; }
                case "smoothstep" -> {
                    requireCount(name, a, 3);
                    double t = Math.max(0.0, Math.min(1.0, (a.get(2) - a.get(0)) / (a.get(1) - a.get(0))));
                    yield t * t * (3.0 - 2.0 * t);
                }
                case "smooth" -> {
                    if (a.size() < 1 || a.size() > 4) {
                        throw failure("smooth expects 1 to 4 arguments");
                    }
                    // Iris smooth() is stateful; return its target until the per-uniform
                    // temporal state is available. This preserves valid expression evaluation.
                    yield a.size() >= 3 && a.size() == 4 ? a.get(1) : a.get(0);
                }
                case "if" -> { requireCount(name, a, 3); yield truth(a.get(0)) ? a.get(1) : a.get(2); }
                default -> throw failure("Unsupported Iris expression function '" + name + "'");
            };
        }

        private double unary(String name, java.util.List<Double> args, java.util.function.DoubleUnaryOperator op) {
            requireCount(name, args, 1);
            return op.applyAsDouble(args.getFirst());
        }

        private double binary(String name, java.util.List<Double> args, java.util.function.DoubleBinaryOperator op) {
            requireCount(name, args, 2);
            return op.applyAsDouble(args.get(0), args.get(1));
        }

        private double aggregate(String name, java.util.List<Double> args, java.util.function.DoubleBinaryOperator operation) {
            if (args.size() < 2) throw failure(name + " expects at least two arguments");
            double value = args.getFirst();
            for (int i = 1; i < args.size(); i++) value = operation.applyAsDouble(value, args.get(i));
            return value;
        }

        private void requireCount(String name, java.util.List<Double> args, int count) {
            if (args.size() != count) throw failure(name + " expects " + count + " argument(s)");
        }

        private double number() {
            int start = cursor;
            while (!end() && (Character.isDigit(source.charAt(cursor)) || source.charAt(cursor) == '.')) cursor++;
            if (!end() && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
                cursor++;
                if (!end() && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) cursor++;
                while (!end() && Character.isDigit(source.charAt(cursor))) cursor++;
            }
            try { return Double.parseDouble(source.substring(start, cursor)); }
            catch (NumberFormatException ex) { throw failure("Invalid numeric literal"); }
        }

        private String identifier() {
            whitespace();
            int start = cursor;
            if (!end() && (Character.isLetter(source.charAt(cursor)) || source.charAt(cursor) == '_')) {
                cursor++;
                while (!end() && (Character.isLetterOrDigit(source.charAt(cursor)) || source.charAt(cursor) == '_' || source.charAt(cursor) == '.')) cursor++;
            }
            return source.substring(start, cursor);
        }

        private boolean take(String token) {
            whitespace();
            if (!source.startsWith(token, cursor)) return false;
            if ((token.equals("<") || token.equals(">") || token.equals("=") || token.equals("!"))
                    && cursor + 1 < source.length() && source.charAt(cursor + 1) == '=') return false;
            if ((token.equals("&") || token.equals("|")) && cursor + 1 < source.length()
                    && source.charAt(cursor + 1) == source.charAt(cursor)) return false;
            cursor += token.length();
            return true;
        }

        private void require(String token) {
            if (!take(token)) throw failure("Expected '" + token + "'");
        }

        private void whitespace() {
            while (!end() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        }

        private boolean end() { return cursor >= source.length(); }
        private IllegalArgumentException failure(String message) {
            return new IllegalArgumentException(message + " at character " + cursor + " in '" + source + "'");
        }
        private static boolean truth(double value) { return value != 0.0 && !Double.isNaN(value); }
    }
}
