package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL44C;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns Iris image.* resources, sampler aliases, and temporary OpenGL image-unit bindings. */
final class IrisImageResources implements AutoCloseable {
    private static final Pattern IMAGE = Pattern.compile("^image\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$");
    private static final long MAX_IMAGE_BYTES = 512L * 1024L * 1024L;
    private final Map<String, Image> images;
    private final Map<String, Image> samplers;
    private final List<ImageBinding> previousBindings = new ArrayList<>();
    private boolean closed;

    private IrisImageResources(Map<String, Image> images, Map<String, Image> samplers) {
        this.images = Map.copyOf(images);
        this.samplers = Map.copyOf(samplers);
    }

    static IrisImageResources empty() { return new IrisImageResources(Map.of(), Map.of()); }

    static IrisImageResources load(String source, Map<String, String> defines) throws java.io.IOException {
        Map<String, ImageSpec> specs = parse(source, defines);
        Map<String, Image> images = new LinkedHashMap<>();
        Map<String, Image> samplers = new LinkedHashMap<>();
        long bytes = 0;
        try {
            for (Map.Entry<String, ImageSpec> entry : specs.entrySet()) {
                ImageSpec spec = entry.getValue();
                bytes = Math.addExact(bytes, Math.multiplyExact((long) spec.width * spec.height * spec.depth,
                        bytesPerTexel(spec.internalFormat)));
                if (bytes > MAX_IMAGE_BYTES) throw new java.io.IOException(
                        "Iris custom images exceed CAUSTIQ's 512 MiB allocation budget.");
                Image image = create(entry.getKey(), spec);
                images.put(entry.getKey(), image);
                if (samplers.putIfAbsent(spec.sampler, image) != null) {
                    throw new java.io.IOException("Iris image sampler alias is declared more than once: " + spec.sampler);
                }
            }
            return new IrisImageResources(images, samplers);
        } catch (RuntimeException | java.io.IOException failure) {
            images.values().forEach(image -> GL11C.glDeleteTextures(image.texture));
            throw failure;
        }
    }

    Set<String> imageNames() { return images.keySet(); }

    boolean bindSampler(String name, int unit, int uniformLocation) {
        Image image = samplers.get(name);
        if (image == null) return false;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(image.target, image.texture);
        GL20C.glUniform1i(uniformLocation, unit);
        return true;
    }

    void bindImages(int program, Set<String> names) {
        if (closed || names.isEmpty()) return;
        int unit = 0;
        int maximum = GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_UNITS);
        for (String name : names) {
            Image image = images.get(name);
            if (image == null) {
                throw new IllegalStateException("Iris program references image '" + name
                        + "' without a matching image declaration in shaders.properties.");
            }
            int location = GL20C.glGetUniformLocation(program, name);
            if (location < 0) continue;
            if (unit >= maximum) throw new IllegalStateException("Iris image bindings exceed GL_MAX_IMAGE_UNITS.");
            captureBinding(unit);
            GL42C.glBindImageTexture(unit, image.texture, 0, image.depth > 1,
                    0, GL42C.GL_READ_WRITE, image.internalFormat);
            GL20C.glUniform1i(location, unit++);
        }
    }

    void bindTerrainImages(int program) { bindImages(program, images.keySet()); }

    void restoreImages() {
        for (int i = previousBindings.size() - 1; i >= 0; i--) {
            ImageBinding b = previousBindings.get(i);
            GL42C.glBindImageTexture(b.unit, b.texture, b.level, b.layered, b.layer, b.access, b.format);
        }
        previousBindings.clear();
    }

    void clear(String name) {
        Image image = images.get(name);
        if (image != null) {
            GL44C.glClearTexImage(image.texture, 0, image.externalFormat,
                    GL11C.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(16));
        GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
    }

    int[] dispatchGroups(Set<String> outputs, int localX, int localY, int localZ,
                         boolean usesGlobalY, boolean usesGlobalZ) {
        int width = 1, height = 1, depth = 1;
        for (String name : outputs) {
            Image image = images.get(name);
            if (image == null) continue;
            width = Math.max(width, image.width);
            if (usesGlobalY) height = Math.max(height, image.height);
            if (usesGlobalZ) depth = Math.max(depth, image.depth);
        }
        return new int[]{ceilDiv(width, localX), ceilDiv(height, localY), ceilDiv(depth, localZ)};
    }

    private void captureBinding(int unit) {
        for (ImageBinding b : previousBindings) if (b.unit == unit) return;
        previousBindings.add(new ImageBinding(unit,
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, unit),
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, unit),
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, unit) != 0,
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, unit),
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, unit),
                GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, unit)));
    }

    static Map<String, ImageSpec> parse(String source, Map<String, String> defines) throws java.io.IOException {
        Map<String, ImageSpec> result = new LinkedHashMap<>();
        Deque<Conditional> stack = new ArrayDeque<>();
        boolean active = true;
        for (String raw : source.split("\\R", -1)) {
            String line = raw.strip();
            if (line.startsWith("#ifdef ")) {
                boolean condition = enabled(defines, line.substring(7).strip());
                stack.push(new Conditional(active, condition));
                active &= condition;
                continue;
            }
            if (line.startsWith("#ifndef ")) {
                boolean condition = !enabled(defines, line.substring(8).strip());
                stack.push(new Conditional(active, condition));
                active &= condition;
                continue;
            }
            if (line.startsWith("#if ")) {
                boolean condition = evaluate(line.substring(4).strip(), defines);
                stack.push(new Conditional(active, condition));
                active &= condition;
                continue;
            }
            if (line.startsWith("#elif ")) {
                if (stack.isEmpty()) throw new java.io.IOException("Unmatched #elif in shaders.properties.");
                Conditional frame = stack.peek();
                boolean condition = !frame.branchTaken && evaluate(line.substring(6).strip(), defines);
                frame.branchTaken |= condition;
                active = frame.parentActive && condition;
                continue;
            }
            if (line.equals("#else")) {
                if (stack.isEmpty()) throw new java.io.IOException("Unmatched #else in shaders.properties.");
                Conditional frame = stack.peek();
                boolean condition = !frame.branchTaken;
                frame.branchTaken = true;
                active = frame.parentActive && condition;
                continue;
            }
            if (line.equals("#endif")) {
                if (stack.isEmpty()) throw new java.io.IOException("Unmatched #endif in shaders.properties.");
                active = stack.pop().parentActive;
                continue;
            }
            if (!active || line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            Matcher matcher = IMAGE.matcher(line);
            if (!matcher.matches()) continue;
            String name = matcher.group(1);
            String[] v = matcher.group(2).split("\\s+");
            if (v.length != 8 && v.length != 9) {
                throw new java.io.IOException("Iris image." + name
                        + " must declare sampler, format, internal format, type, clear, nearest and 2D/3D dimensions.");
            }
            int width = dimension(v[6], defines, name);
            int height = dimension(v[7], defines, name);
            int depth = v.length == 9 ? dimension(v[8], defines, name) : 1;
            if (width < 1 || height < 1 || depth < 1 || width > 8192 || height > 8192 || depth > 2048) {
                throw new java.io.IOException("Iris image." + name + " has invalid dimensions "
                        + width + "x" + height + "x" + depth + ".");
            }
            ImageSpec spec = new ImageSpec(v[0], externalFormat(v[1]), internalFormat(v[2]),
                    dataType(v[3]), Boolean.parseBoolean(v[4]), Boolean.parseBoolean(v[5]),
                    width, height, depth);
            if (result.putIfAbsent(name, spec) != null) {
                throw new java.io.IOException("Iris image." + name + " is duplicated in the active properties branch.");
            }
            if (result.size() > 64) throw new java.io.IOException("Shader pack declares more than 64 custom images.");
        }
        if (!stack.isEmpty()) throw new java.io.IOException("Unclosed conditional block in shaders.properties.");
        return result;
    }

    private static boolean evaluate(String expression, Map<String, String> defines) throws java.io.IOException {
        String value = stripOuterParentheses(expression.strip());
        int split = topLevelOperator(value, "||");
        if (split >= 0) return evaluate(value.substring(0, split), defines)
                || evaluate(value.substring(split + 2), defines);
        split = topLevelOperator(value, "&&");
        if (split >= 0) return evaluate(value.substring(0, split), defines)
                && evaluate(value.substring(split + 2), defines);
        Matcher defined = Pattern.compile("^(!)?\\s*defined\\s*\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)?$").matcher(value);
        if (defined.matches()) {
            boolean present = enabled(defines, defined.group(2));
            return defined.group(1) == null ? present : !present;
        }
        Matcher comparison = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|<=|>=|<|>)\\s*(-?\\d+)$").matcher(value);
        if (comparison.matches()) {
            int actual;
            try { actual = Integer.parseInt(defines.getOrDefault(comparison.group(1), "0")); }
            catch (NumberFormatException ex) { throw new java.io.IOException("Non-numeric image conditional macro: " + comparison.group(1), ex); }
            int expected = Integer.parseInt(comparison.group(3));
            return switch (comparison.group(2)) {
                case "==" -> actual == expected;
                case "!=" -> actual != expected;
                case "<" -> actual < expected;
                case "<=" -> actual <= expected;
                case ">" -> actual > expected;
                case ">=" -> actual >= expected;
                default -> false;
            };
        }
        if (value.startsWith("!")) return !evaluate(value.substring(1), defines);
        if (value.equals("0") || value.equalsIgnoreCase("false")) return false;
        if (value.equals("1") || value.equalsIgnoreCase("true")) return true;
        if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) return enabled(defines, value);
        return false; // Unrelated platform/version conditions are inactive unless explicitly defined.
    }

    private static String stripOuterParentheses(String value) {
        while (value.startsWith("(") && value.endsWith(")")) {
            int depth = 0;
            boolean wrapsAll = true;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == '(') depth++;
                else if (value.charAt(i) == ')') depth--;
                if (depth == 0 && i < value.length() - 1) { wrapsAll = false; break; }
            }
            if (!wrapsAll) break;
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static int topLevelOperator(String value, String operator) {
        int depth = 0;
        for (int i = 0; i <= value.length() - operator.length(); i++) {
            if (value.charAt(i) == '(') depth++;
            else if (value.charAt(i) == ')') depth--;
            if (depth == 0 && value.startsWith(operator, i)) return i;
        }
        return -1;
    }

    private static boolean enabled(Map<String, String> defines, String name) {
        String value = defines.get(name);
        return value != null && !value.equalsIgnoreCase("false") && !value.equals("0");
    }

    private static int dimension(String token, Map<String, String> defines, String image) throws java.io.IOException {
        String value = defines.getOrDefault(token, token);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) {
            throw new java.io.IOException("Iris image." + image + " dimension '" + token
                    + "' is not a resolved integer (selected value: " + value + ").", ex);
        }
    }

    private static int externalFormat(String name) throws java.io.IOException {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "rgba" -> GL11C.GL_RGBA;
            case "rgb" -> GL11C.GL_RGB;
            case "rg" -> GL30C.GL_RG;
            case "red" -> GL11C.GL_RED;
            case "red_integer" -> GL30C.GL_RED_INTEGER;
            case "rg_integer" -> GL30C.GL_RG_INTEGER;
            case "rgba_integer" -> GL30C.GL_RGBA_INTEGER;
            default -> throw new java.io.IOException("Unsupported Iris image format: " + name);
        };
    }

    private static int internalFormat(String name) throws java.io.IOException {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "rgba8" -> GL11C.GL_RGBA8;
            case "rgba16f" -> GL30C.GL_RGBA16F;
            case "rgba32f" -> GL30C.GL_RGBA32F;
            case "r8ui" -> GL30C.GL_R8UI;
            case "r16f" -> GL30C.GL_R16F;
            case "r32f" -> GL30C.GL_R32F;
            case "rg16f" -> GL30C.GL_RG16F;
            default -> throw new java.io.IOException("Unsupported Iris image internal format: " + name);
        };
    }

    private static int dataType(String name) throws java.io.IOException {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "unsigned_byte" -> GL11C.GL_UNSIGNED_BYTE;
            case "byte" -> GL11C.GL_BYTE;
            case "unsigned_short" -> GL11C.GL_UNSIGNED_SHORT;
            case "short" -> GL11C.GL_SHORT;
            case "half_float" -> GL30C.GL_HALF_FLOAT;
            case "float" -> GL11C.GL_FLOAT;
            case "unsigned_int" -> GL11C.GL_UNSIGNED_INT;
            case "int" -> GL11C.GL_INT;
            default -> throw new java.io.IOException("Unsupported Iris image data type: " + name);
        };
    }

    private static int bytesPerTexel(int format) {
        if (format == GL30C.GL_RGBA16F) return 8;
        if (format == GL30C.GL_RGBA32F) return 16;
        if (format == GL11C.GL_RGBA8) return 4;
        if (format == GL30C.GL_R8UI) return 1;
        if (format == GL30C.GL_R16F) return 2;
        if (format == GL30C.GL_R32F || format == GL30C.GL_RG16F) return 4;
        return 4;
    }

    private static Image create(String name, ImageSpec spec) {
        int target = spec.depth > 1 ? GL12C.GL_TEXTURE_3D : GL11C.GL_TEXTURE_2D;
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousBinding = GL11C.glGetInteger(target == GL12C.GL_TEXTURE_3D
                ? GL12C.GL_TEXTURE_BINDING_3D : GL11C.GL_TEXTURE_BINDING_2D);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(target, texture);
        int filter = spec.nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, filter);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, filter);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        if (target == GL12C.GL_TEXTURE_3D) {
            GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
            GL12C.glTexImage3D(target, 0, spec.internalFormat, spec.width, spec.height, spec.depth,
                    0, spec.externalFormat, spec.dataType, (ByteBuffer) null);
        } else {
            GL11C.glTexImage2D(target, 0, spec.internalFormat, spec.width, spec.height,
                    0, spec.externalFormat, spec.dataType, (ByteBuffer) null);
        }
        GL11C.glBindTexture(target, previousBinding);
        GL13C.glActiveTexture(previousActive);
        GL44C.glClearTexImage(texture, 0, spec.externalFormat, GL11C.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(16));
        return new Image(name, spec.sampler, texture, target, spec.internalFormat, spec.externalFormat,
                spec.width, spec.height, spec.depth);
    }

    private static int ceilDiv(int value, int divisor) { return (value + divisor - 1) / divisor; }

    @Override public void close() {
        if (closed) return;
        restoreImages();
        images.values().forEach(image -> GL11C.glDeleteTextures(image.texture));
        closed = true;
    }

    private record Image(String name, String sampler, int texture, int target, int internalFormat,
                         int externalFormat, int width, int height, int depth) { }
    record ImageSpec(String sampler, int externalFormat, int internalFormat, int dataType,
                             boolean clear, boolean nearest, int width, int height, int depth) { }
    private record ImageBinding(int unit, int texture, int level, boolean layered, int layer, int access, int format) { }
    private static final class Conditional {
        final boolean parentActive;
        boolean branchTaken;
        Conditional(boolean parentActive, boolean condition) {
            this.parentActive = parentActive;
            this.branchTaken = condition;
        }
    }
}
