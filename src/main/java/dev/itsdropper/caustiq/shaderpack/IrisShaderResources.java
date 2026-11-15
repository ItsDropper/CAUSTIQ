package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns shader-pack custom 2D/3D textures and Iris custom uniform/variable expressions. */
public final class IrisShaderResources implements AutoCloseable {
    private static final Pattern TEXTURE = Pattern.compile("^texture\\.([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?(?:\\.\\d+)?$");
    private static final Pattern EXPRESSION = Pattern.compile("^(uniform|variable)\\.(float|int|bool|vec[234]|ivec[234]|bvec[234])\\.([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ANY_EXPRESSION = Pattern.compile("^(uniform|variable)\\..+");
    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+([^\\r\\n]+))?");
    private static final int MAX_TEXTURES = 64;
    private static final long MAX_DECODED_BYTES = 128L * 1024L * 1024L;

    private final Map<String, TextureAsset> textureAssets;
    private final Map<String, Integer> glTextures = new HashMap<>();
    private final IrisImageResources imageResources;
    private int terrainActiveTexture;
    private int[] terrainUnits = new int[0];
    private int[] terrainBindings = new int[0];
    private int[] terrainTargets = new int[0];
    private final Map<String, TypedExpression> uniforms;
    private final Map<String, TypedExpression> variables;
    private final Map<String, Double> optionDefines;
    private boolean closed;

    private IrisShaderResources(Map<String, TextureAsset> textures,
                                Map<String, TypedExpression> uniforms,
                                Map<String, TypedExpression> variables,
                                Map<String, Double> optionDefines,
                                IrisImageResources imageResources) {
        this.textureAssets = Map.copyOf(textures);
        this.uniforms = Map.copyOf(uniforms);
        this.variables = Map.copyOf(variables);
        this.optionDefines = Map.copyOf(optionDefines);
        this.imageResources = imageResources;
    }

    public static IrisShaderResources empty() {
        return new IrisShaderResources(Map.of(), Map.of(), Map.of(), Map.of(), IrisImageResources.empty());
    }

    public static IrisShaderResources load(Map<String, String> properties, ShaderPackSources sources)
            throws IOException {
        return load(properties, sources, Map.of());
    }

    public static IrisShaderResources load(Map<String, String> properties, ShaderPackSources sources,
                                           Map<String, String> defines) throws IOException {
        Map<String, TextureAsset> textures = new LinkedHashMap<>();
        Map<String, TypedExpression> uniforms = new LinkedHashMap<>();
        Map<String, TypedExpression> variables = new LinkedHashMap<>();
        long decodedBytes = 0L;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            Matcher texture = TEXTURE.matcher(property.getKey());
            if (texture.matches()) {
                if (textures.size() >= MAX_TEXTURES) {
                    throw new IOException("Shader pack declares more than " + MAX_TEXTURES + " custom textures.");
                }
                String scope = texture.group(2) == null ? "" : texture.group(1);
                String name = texture.group(2) == null ? texture.group(1) : texture.group(2);
                String key = scope.isEmpty() ? name : scope + "::" + name;
                String[] declaration = unquote(property.getValue().trim()).split("\\s+");
                if (declaration.length == 0 || declaration[0].isBlank()) {
                    throw new IOException("Custom texture '" + key + "' has an empty asset path.");
                }
                byte[] encoded = readTextureBytes(sources, declaration[0]);
                TextureAsset asset;
                if (declaration.length == 1) {
                    ImageData decoded = decodeImage(encoded, key, declaration[0]);
                    int width = decoded.width(), height = decoded.height();
                    decodedBytes += (long) width * height * 4L;
                    if (decodedBytes > MAX_DECODED_BYTES) {
                        throw new IOException("Decoded custom textures exceed the 128 MiB memory budget.");
                    }
                    BufferedImage image = decoded.image();
                    ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int argb = image.getRGB(x, y);
                        pixels.put((byte) ((argb >>> 16) & 0xff));
                        pixels.put((byte) ((argb >>> 8) & 0xff));
                        pixels.put((byte) (argb & 0xff));
                        pixels.put((byte) ((argb >>> 24) & 0xff));
                    }
                    pixels.flip();
                    asset = new TextureAsset(GL11C.GL_TEXTURE_2D, GL30C.GL_RGBA8,
                            width, height, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
                } else {
                    asset = decodeTypedTexture(key, declaration, encoded);
                    decodedBytes += asset.pixels().remaining();
                    if (decodedBytes > MAX_DECODED_BYTES) {
                        throw new IOException("Decoded custom textures exceed the 128 MiB memory budget.");
                    }
                }
                textures.put(key, asset);
                continue;
            }
            Matcher expression = EXPRESSION.matcher(property.getKey());
            if (!expression.matches()) {
                if (ANY_EXPRESSION.matcher(property.getKey()).matches()) {
                    throw new IOException("Unsupported Iris custom uniform/variable declaration: " + property.getKey());
                }
                continue;
            }
            String kind = expression.group(1);
            String type = expression.group(2);
            String name = expression.group(3);
            TypedExpression parsed = new TypedExpression(type, property.getValue().trim());
            (kind.equals("uniform") ? uniforms : variables).put(name, parsed);
        }
        String propertiesSource;
        try { propertiesSource = sources.read("shaders.properties"); }
        catch (IOException missing) { propertiesSource = ""; }
        IrisImageResources images = IrisImageResources.load(propertiesSource, resolveShaderPackDefines(sources, defines));
        return new IrisShaderResources(textures, uniforms, variables, numericOptionDefines(defines), images);
    }

    private static Map<String, Double> numericOptionDefines(Map<String, String> defines) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> define : defines.entrySet()) {
            String raw = define.getValue() == null ? "" : define.getValue().trim();
            if (raw.equalsIgnoreCase("true")) {
                values.put(define.getKey(), 1.0);
                continue;
            }
            if (raw.equalsIgnoreCase("false")) {
                values.put(define.getKey(), 0.0);
                continue;
            }
            String numeric = raw.replaceFirst("(?i)[uif]+$", "");
            try {
                double value = numeric.startsWith("0x") || numeric.startsWith("0X")
                        ? Long.parseUnsignedLong(numeric.substring(2), 16)
                        : Double.parseDouble(numeric);
                if (Double.isFinite(value)) values.put(define.getKey(), value);
            } catch (NumberFormatException ignored) {
                // Non-numeric preprocessor options remain available to GLSL preprocessing only.
            }
        }
        return Map.copyOf(values);
    }

    public java.util.Set<String> textureNames() { return textureAssets.keySet(); }
    public java.util.Set<String> imageNames() { return imageResources.imageNames(); }
    public void bindProgramImages(int program, java.util.Set<String> names) { imageResources.bindImages(program, names); }
    public void restoreProgramImages() { imageResources.restoreImages(); }
    public void clearImage(String name) { imageResources.clear(name); }
    public int[] computeDispatchGroups(IrisGlProgram program) {
        return imageResources.dispatchGroups(program.images(), program.localSizeX(), program.localSizeY(),
                program.localSizeZ(), program.usesGlobalY(), program.usesGlobalZ());
    }
    public java.util.Set<String> uniformNames() { return uniforms.keySet(); }
    public java.util.Set<String> variableNames() { return variables.keySet(); }

    public boolean hasTexture(String sampler) {
        return textureAssets.containsKey(sampler)
                || (sampler.equals("noisetex") && textureAssets.containsKey("noise"));
    }

    /** Binds all declared custom sampler2D textures to free units for Nvidium's terrain program. */
    public void bindTerrainTextures(int program, int firstUnit) {
        restoreTerrainTextures();
        terrainActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        java.util.ArrayList<Integer> units = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> bindings = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> terrainTargetList = new java.util.ArrayList<>();
        int unit = firstUnit;
        int maximum = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        for (Map.Entry<String, TextureAsset> entry : textureAssets.entrySet()) {
            String key = entry.getKey();
            if (key.contains("::") && !key.startsWith("gbuffers_terrain::")) continue;
            String name = key.contains("::") ? key.substring(key.indexOf("::") + 2) : key;
            String sampler = name.equals("noise") ? "noisetex" : name;
            int location = GL20C.glGetUniformLocation(program, sampler);
            if (location < 0) continue;
            if (unit >= maximum) {
                terrainUnits = units.stream().mapToInt(Integer::intValue).toArray();
                terrainBindings = bindings.stream().mapToInt(Integer::intValue).toArray();
                terrainTargets = terrainTargetList.stream().mapToInt(Integer::intValue).toArray();
                GL13C.glActiveTexture(terrainActiveTexture);
                restoreTerrainTextures();
                throw new IllegalStateException("Terrain shader custom textures exceed the fragment-stage texture-unit limit.");
            }
            TextureAsset asset = entry.getValue();
            int texture = glTextures.computeIfAbsent(key, ignored -> createTexture(asset));
            int textureUnit = GL13C.GL_TEXTURE0 + unit;
            GL13C.glActiveTexture(textureUnit);
            units.add(textureUnit);
            bindings.add(GL11C.glGetInteger(textureBindingEnum(asset.target())));
            GL11C.glBindTexture(asset.target(), texture);
            GL20C.glUniform1i(location, unit);
            terrainTargetList.add(asset.target());
            unit++;
        }
        terrainUnits = units.stream().mapToInt(Integer::intValue).toArray();
        terrainBindings = bindings.stream().mapToInt(Integer::intValue).toArray();
        terrainTargets = terrainTargetList.stream().mapToInt(Integer::intValue).toArray();
        imageResources.bindTerrainImages(program);
        GL13C.glActiveTexture(terrainActiveTexture);
    }

    /** Restores texture units temporarily used by the terrain program. */
    public void restoreTerrainTextures() {
        imageResources.restoreImages();
        if (terrainUnits.length == 0) return;
        for (int i = 0; i < terrainUnits.length; i++) {
            GL13C.glActiveTexture(terrainUnits[i]);
            int target = i < terrainTargets.length ? terrainTargets[i] : GL11C.GL_TEXTURE_2D;
            GL11C.glBindTexture(target, terrainBindings[i]);
        }
        GL13C.glActiveTexture(terrainActiveTexture);
        terrainUnits = new int[0];
        terrainBindings = new int[0];
        terrainTargets = new int[0];
    }

    /** Binds a declared custom texture and returns true when the sampler was resolved. */
    public boolean bindTexture(String sampler, int unit, int uniformLocation) {
        return bindTexture(sampler, "", unit, uniformLocation);
    }

    public boolean bindTexture(String sampler, String programName, int unit, int uniformLocation) {
        if (imageResources.bindSampler(sampler, unit, uniformLocation)) return true;
        String scoped = programName == null || programName.isBlank() ? "" : programName + "::" + sampler;
        String key = textureAssets.containsKey(scoped) ? scoped : sampler;
        if (!textureAssets.containsKey(key) && sampler.equals("noisetex") && textureAssets.containsKey("noise")) {
            key = "noise";
        }
        TextureAsset asset = textureAssets.get(key);
        if (asset == null) return false;
        int texture = glTextures.computeIfAbsent(key, ignored -> createTexture(asset));
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(asset.target(), texture);
        GL20C.glUniform1i(uniformLocation, unit);
        return true;
    }

    public Map<String, double[]> evaluateUniformValues(Map<String, Double> baseVariables) {
        return evaluateUniformValues(baseVariables, ignored -> true);
    }

    private Map<String, double[]> evaluateUniformValues(Map<String, Double> baseVariables,
                                                        java.util.function.Predicate<String> include) {
        Map<String, Double> context = new HashMap<>(optionDefines);
        context.putAll(baseVariables);
        Map<String, Double> evaluatedVariables = new HashMap<>();
        List<String> variableStack = new ArrayList<>();
        Map<String, double[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, TypedExpression> uniform : uniforms.entrySet()) {
            if (!include.test(uniform.getKey())) continue;
            double[] values = evaluateTyped(uniform.getValue(), context, evaluatedVariables, variableStack);
            for (double component : values) {
                if (!Double.isFinite(component)) {
                    throw new IllegalArgumentException("Iris custom uniform '" + uniform.getKey()
                            + "' evaluated to a non-finite value.");
                }
            }
            result.put(uniform.getKey(), values);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public void uploadUniforms(int program, Map<String, Double> baseVariables) {
        if (uniforms.isEmpty()) return;
        evaluateUniformValues(baseVariables, name -> GL20C.glGetUniformLocation(program, name) >= 0)
                .forEach((name, values) -> upload(program, name, uniforms.get(name).type(), values));
    }

    private double resolveVariable(String name, Map<String, Double> context,
                                   Map<String, Double> evaluated, List<String> stack) {
        Double value = context.get(name);
        if (value != null) return value;

        int separator = name.lastIndexOf('.');
        if (separator > 0 && separator < name.length() - 1) {
            String vectorName = name.substring(0, separator);
            TypedExpression vector = variables.get(vectorName);
            if (vector != null && componentCount(vector.type()) > 1) {
                return resolveVectorComponent(vectorName, name.substring(separator + 1), vector.type(),
                        context, evaluated, stack, false);
            }
            vector = uniforms.get(vectorName);
            if (vector != null && componentCount(vector.type()) > 1) {
                return resolveVectorComponent(vectorName, name.substring(separator + 1), vector.type(),
                        context, evaluated, stack, true);
            }
        }
        TypedExpression scalarUniform = uniforms.get(name);
        if (scalarUniform != null && componentCount(scalarUniform.type()) == 1) {
            String marker = "uniform:" + name;
            if (stack.contains(marker)) {
                throw new IllegalArgumentException("Cyclic Iris uniform dependency: "
                        + String.join(" -> ", stack) + " -> " + marker);
            }
            stack.add(marker);
            double value = IrisExpression.evaluate(scalarUniform.expression(), identifier ->
                    resolveVariable(identifier, context, evaluated, stack));
            stack.removeLast();
            return value;
        }
        return evaluateVariable(name, context, evaluated, stack);
    }

    private static int vectorComponent(String component) {
        return switch (component) {
            case "x", "r" -> 0;
            case "y", "g" -> 1;
            case "z", "b" -> 2;
            case "w", "a" -> 3;
            default -> throw new IllegalArgumentException("Unsupported Iris vector component '" + component + "'.");
        };
    }

    private double resolveVectorComponent(String name, String componentName, String type,
                                           Map<String, Double> context, Map<String, Double> evaluated,
                                           List<String> stack, boolean uniform) {
        int component = vectorComponent(componentName);
        double[] components = uniform
                ? evaluateUniformVectorTyped(name, type, context, evaluated, stack)
                : evaluateVariableTyped(name, type, context, evaluated, stack);
        if (component >= components.length) {
            throw new IllegalArgumentException("Iris vector " + (uniform ? "uniform" : "variable") + " '" + name
                    + "' has no component '" + componentName + "'.");
        }
        return components[component];
    }

    private double[] evaluateUniformVectorTyped(String name, String expectedType, Map<String, Double> context,
                                                Map<String, Double> evaluated, List<String> stack) {
        TypedExpression expression = uniforms.get(name);
        if (expression == null) throw new IllegalArgumentException("Unknown Iris vector uniform '" + name + "'.");
        if (!expression.type().equals(expectedType)) {
            throw new IllegalArgumentException("Iris uniform '" + name + "' has type " + expression.type()
                    + " but " + expectedType + " was required.");
        }
        String marker = "uniform:" + name;
        if (stack.contains(marker)) {
            throw new IllegalArgumentException("Cyclic Iris uniform dependency: "
                    + String.join(" -> ", stack) + " -> " + marker);
        }
        stack.add(marker);
        double[] value = evaluateVectorExpression(expression, context, evaluated, stack);
        stack.removeLast();
        return value;
    }

    private double[] evaluateVectorReference(String name, String expectedType, Map<String, Double> context,
                                             Map<String, Double> evaluated, List<String> stack) {
        if (variables.containsKey(name)) {
            return evaluateVariableTyped(name, expectedType, context, evaluated, stack);
        }
        return evaluateUniformVectorTyped(name, expectedType, context, evaluated, stack);
    }

    private double evaluateVariable(String name, Map<String, Double> context,
                                    Map<String, Double> evaluated, List<String> stack) {
        Double known = evaluated.get(name);
        if (known != null) return known;
        TypedExpression expression = variables.get(name);
        if (expression == null) throw new IllegalArgumentException("Unknown Iris custom-uniform variable '" + name + "'.");
        if (componentCount(expression.type()) != 1) {
            throw new IllegalArgumentException("Iris vector variable '" + name + "' cannot be used as a scalar expression.");
        }
        if (stack.contains(name)) throw new IllegalArgumentException("Cyclic Iris variable dependency: " + String.join(" -> ", stack) + " -> " + name);
        stack.add(name);
        double value = IrisExpression.evaluate(expression.expression(), identifier ->
                resolveVariable(identifier, context, evaluated, stack));
        stack.removeLast();
        evaluated.put(name, value);
        return value;
    }

    private double[] evaluateTyped(TypedExpression expression, Map<String, Double> context,
                                   Map<String, Double> evaluated, List<String> stack) {
        if (componentCount(expression.type()) == 1) {
            return new double[]{IrisExpression.evaluate(expression.expression(), name -> {
                Double value = context.get(name);
                return value != null ? value : resolveVariable(name, context, evaluated, stack);
            })};
        }
        return evaluateVectorExpression(expression, context, evaluated, stack);
    }

    private double[] evaluateVariableTyped(String name, String expectedType, Map<String, Double> context,
                                           Map<String, Double> evaluated, List<String> stack) {
        TypedExpression expression = variables.get(name);
        if (expression == null) throw new IllegalArgumentException("Unknown Iris vector variable '" + name + "'.");
        if (!expression.type().equals(expectedType)) {
            throw new IllegalArgumentException("Iris variable '" + name + "' has type " + expression.type()
                    + " but " + expectedType + " was required.");
        }
        if (componentCount(expression.type()) == 1) {
            return new double[]{evaluateVariable(name, context, evaluated, stack)};
        }
        if (stack.contains(name)) throw new IllegalArgumentException("Cyclic Iris variable dependency: " + String.join(" -> ", stack) + " -> " + name);
        stack.add(name);
        double[] value = evaluateVectorExpression(expression, context, evaluated, stack);
        stack.removeLast();
        return value;
    }

    private double[] evaluateVectorExpression(TypedExpression expression, Map<String, Double> context,
                                              Map<String, Double> evaluated, List<String> stack) {
        String source = expression.expression().trim();
        if (source.matches("[A-Za-z_][A-Za-z0-9_]*")
                && (variables.containsKey(source) || uniforms.containsKey(source))) {
            return evaluateVectorReference(source, expression.type(), context, evaluated, stack);
        }
        String prefix = expression.type() + "(";
        if (source.startsWith(prefix) && source.endsWith(")")) {
            source = source.substring(prefix.length(), source.length() - 1);
        }
        List<String> parts = splitComponents(source);
        int components = componentCount(expression.type());
        if (parts.size() == 1 && components > 1) {
            double splat = evaluateScalar(parts.getFirst(), context, evaluated, stack);
            double[] values = new double[components];
            java.util.Arrays.fill(values, splat);
            return values;
        }
        if (parts.size() != components) {
            throw new IllegalArgumentException("Iris " + expression.type() + " expression requires "
                    + components + " components: " + expression.expression());
        }
        double[] values = new double[components];
        for (int i = 0; i < components; i++) {
            values[i] = evaluateScalar(parts.get(i), context, evaluated, stack);
        }
        return values;
    }

    private double evaluateScalar(String source, Map<String, Double> context,
                                  Map<String, Double> evaluated, List<String> stack) {
        return IrisExpression.evaluate(source, name -> {
            Double value = context.get(name);
            return value != null ? value : resolveVariable(name, context, evaluated, stack);
        });
    }

    private static List<String> splitComponents(String source) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(source.substring(start).trim());
        return parts;
    }

    private static int componentCount(String type) {
        if ((type.startsWith("vec") || type.startsWith("ivec") || type.startsWith("bvec"))
                && type.length() >= 4) {
            return Character.digit(type.charAt(type.length() - 1), 10);
        }
        return 1;
    }

    private static void upload(int program, String name, String type, double[] v) {
        int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) return;
        boolean integer = type.startsWith("i") || type.startsWith("b") || type.equals("int") || type.equals("bool");
        if (v.length == 1) {
            if (integer) GL20C.glUniform1i(location, (int) v[0]);
            else GL20C.glUniform1f(location, (float) v[0]);
        } else if (integer) {
            int[] a = new int[v.length];
            for (int i = 0; i < v.length; i++) a[i] = (int) v[i];
            switch (a.length) {
                case 2 -> GL20C.glUniform2i(location, a[0], a[1]);
                case 3 -> GL20C.glUniform3i(location, a[0], a[1], a[2]);
                case 4 -> GL20C.glUniform4i(location, a[0], a[1], a[2], a[3]);
                default -> throw new IllegalArgumentException("Unsupported Iris uniform vector width: " + a.length);
            }
        } else {
            switch (v.length) {
                case 2 -> GL20C.glUniform2f(location, (float) v[0], (float) v[1]);
                case 3 -> GL20C.glUniform3f(location, (float) v[0], (float) v[1], (float) v[2]);
                case 4 -> GL20C.glUniform4f(location, (float) v[0], (float) v[1], (float) v[2], (float) v[3]);
                default -> throw new IllegalArgumentException("Unsupported Iris uniform vector width: " + v.length);
            }
        }
    }

    private static ImageData decodeImage(byte[] encoded, String name, String path) throws IOException {
        try (javax.imageio.stream.ImageInputStream input =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (input == null) throw new IOException("Custom texture '" + name + "' has an unreadable image stream: " + path);
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Custom texture '" + name + "' is not a supported image: " + path);
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                        || (long) width * height * 4L > MAX_DECODED_BYTES) {
                    throw new IOException("Custom texture '" + name + "' has invalid or excessive dimensions "
                            + width + "x" + height + ".");
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Custom texture '" + name + "' could not be decoded: " + path);
                return new ImageData(width, height, image);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int createTexture(TextureAsset asset) {
        int target = asset.target();
        int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousBinding = GL11C.glGetInteger(textureBindingEnum(target));
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(target, texture);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        if (target == GL12C.GL_TEXTURE_3D) {
            GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
        }
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        if (target == GL12C.GL_TEXTURE_3D) {
            GL12C.glTexImage3D(target, 0, asset.internalFormat(), asset.width(), asset.height(),
                    asset.depth(), 0, asset.format(), asset.type(), asset.pixels());
        } else {
            GL11C.glTexImage2D(target, 0, asset.internalFormat(), asset.width(), asset.height(),
                    0, asset.format(), asset.type(), asset.pixels());
        }
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
        GL11C.glBindTexture(target, previousBinding);
        GL13C.glActiveTexture(previousActive);
        return texture;
    }

    private static int textureBindingEnum(int target) {
        return target == GL12C.GL_TEXTURE_3D ? GL12C.GL_TEXTURE_BINDING_3D : GL11C.GL_TEXTURE_BINDING_2D;
    }

    private static Map<String, String> resolveShaderPackDefines(ShaderPackSources sources,
                                                                      Map<String, String> selectedDefines) {
        Map<String, String> resolved = new LinkedHashMap<>(selectedDefines);
        resolved.putIfAbsent("MC_VERSION", "12111");
        resolved.putIfAbsent("IS_IRIS", "1");
        try {
            String settings = sources.read("settings.glsl");
            String expanded = new GlslPreprocessor().preprocess(settings, sources::read).source();
            String active = new IrisGlslConditionalPreprocessor().preprocess(expanded, resolved);
            Matcher definitions = DEFINE.matcher(active);
            while (definitions.find()) {
                String value = definitions.group(2);
                if (value == null || value.isBlank()) value = "1";
                else {
                    value = value.replaceFirst("//.*$", "").strip();
                    int whitespace = value.indexOf(' ');
                    if (whitespace >= 0) value = value.substring(0, whitespace);
                }
                resolved.put(definitions.group(1), value);
            }
        } catch (IOException | RuntimeException ignored) {
            // Packs without settings.glsl still use their explicit selected option values.
        }
        return Map.copyOf(resolved);
    }

    private static byte[] readTextureBytes(ShaderPackSources sources, String path) throws IOException {
        if (path.startsWith("minecraft:")) {
            Identifier id;
            try { id = Identifier.parse(path); }
            catch (RuntimeException exception) { throw new IOException("Invalid Minecraft texture identifier: " + path, exception); }
            var resource = Minecraft.getInstance().getResourceManager().getResource(id)
                    .orElseThrow(() -> new IOException("Minecraft texture resource does not exist: " + path));
            try (InputStream stream = resource.open()) {
                return stream.readAllBytes();
            }
        }
        return sources.readBytes(path);
    }

    private static TextureAsset decodeTypedTexture(String name, String[] declaration, byte[] encoded)
            throws IOException {
        if (declaration.length < 7) {
            throw new IOException("Typed custom texture '" + name
                    + "' has an incomplete target/format/dimension declaration.");
        }
        int target = switch (declaration[1].toUpperCase(Locale.ROOT)) {
            case "TEXTURE_2D" -> GL11C.GL_TEXTURE_2D;
            case "TEXTURE_3D" -> GL12C.GL_TEXTURE_3D;
            default -> throw new IOException("Unsupported custom texture target: " + declaration[1]);
        };
        int expectedTokens = target == GL12C.GL_TEXTURE_3D ? 8 : 7;
        if (declaration.length != expectedTokens) {
            throw new IOException("Typed custom texture '" + name + "' has " + declaration.length
                    + " declaration fields; expected " + expectedTokens + " for its texture target.");
        }
        int internalFormat = internalFormat(declaration[2]);
        int width = parseDimension(declaration[3], name);
        int height = parseDimension(declaration[4], name);
        int depth = target == GL12C.GL_TEXTURE_3D ? parseDimension(declaration[5], name) : 1;
        int formatIndex = target == GL12C.GL_TEXTURE_3D ? 6 : 5;
        int typeIndex = formatIndex + 1;
        int format = pixelFormat(declaration[formatIndex]);
        int type = pixelType(declaration[typeIndex]);
        long expected = (long) width * height * depth * componentCount(format) * bytesPerComponent(type);
        if (expected != encoded.length || expected > MAX_DECODED_BYTES) {
            throw new IOException("Custom texture '" + name + "' data size is " + encoded.length
                    + " bytes; expected " + expected + " for its declared dimensions and format.");
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(encoded.length);
        pixels.put(encoded).flip();
        return new TextureAsset(target, internalFormat, width, height, depth, format, type, pixels);
    }

    private static int parseDimension(String value, String name) throws IOException {
        try {
            int dimension = Integer.parseInt(value);
            if (dimension < 1 || dimension > 8192) throw new NumberFormatException();
            return dimension;
        } catch (NumberFormatException exception) {
            throw new IOException("Custom texture '" + name + "' has invalid dimension: " + value, exception);
        }
    }

    private static int internalFormat(String value) throws IOException {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "R8" -> GL30C.GL_R8;
            case "RG8" -> GL30C.GL_RG8;
            case "RGB8" -> GL11C.GL_RGB8;
            case "RGBA8" -> GL30C.GL_RGBA8;
            case "R16F" -> GL30C.GL_R16F;
            case "RG16F" -> GL30C.GL_RG16F;
            case "RGB16F" -> GL30C.GL_RGB16F;
            case "RGBA16F" -> GL30C.GL_RGBA16F;
            case "R32F" -> GL30C.GL_R32F;
            case "RG32F" -> GL30C.GL_RG32F;
            case "RGB32F" -> GL30C.GL_RGB32F;
            case "RGBA32F" -> GL30C.GL_RGBA32F;
            default -> throw new IOException("Unsupported custom texture internal format: " + value);
        };
    }

    private static int pixelFormat(String value) throws IOException {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "RED" -> GL11C.GL_RED;
            case "RG" -> GL30C.GL_RG;
            case "RGB" -> GL11C.GL_RGB;
            case "RGBA" -> GL11C.GL_RGBA;
            default -> throw new IOException("Unsupported custom texture pixel format: " + value);
        };
    }

    private static int pixelType(String value) throws IOException {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "UNSIGNED_BYTE" -> GL11C.GL_UNSIGNED_BYTE;
            case "HALF_FLOAT" -> GL30C.GL_HALF_FLOAT;
            case "FLOAT" -> GL11C.GL_FLOAT;
            default -> throw new IOException("Unsupported custom texture pixel type: " + value);
        };
    }

    private static int componentCount(int format) {
        return switch (format) {
            case GL11C.GL_RED -> 1;
            case GL30C.GL_RG -> 2;
            case GL11C.GL_RGB -> 3;
            case GL11C.GL_RGBA -> 4;
            default -> throw new IllegalArgumentException("Unsupported OpenGL pixel format: " + format);
        };
    }

    private static int bytesPerComponent(int type) throws IOException {
        return switch (type) {
            case GL11C.GL_UNSIGNED_BYTE -> 1;
            case GL30C.GL_HALF_FLOAT -> 2;
            case GL11C.GL_FLOAT -> 4;
            default -> throw new IOException("Unsupported OpenGL pixel type: " + type);
        };
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) return value.substring(1, value.length() - 1);
        return value;
    }

    private static void deleteTexture(int texture) { if (texture != 0) GL11C.glDeleteTextures(texture); }

    @Override public void close() {
        if (closed) return;
        restoreTerrainTextures();
        closed = true;
        glTextures.values().forEach(IrisShaderResources::deleteTexture);
        glTextures.clear();
        imageResources.close();
    }

    private record ImageData(int width, int height, BufferedImage image) { }
    private record TextureAsset(int target, int internalFormat, int width, int height, int depth,
                                int format, int type, ByteBuffer pixels) { }
    private record TypedExpression(String type, String expression) { }
}
