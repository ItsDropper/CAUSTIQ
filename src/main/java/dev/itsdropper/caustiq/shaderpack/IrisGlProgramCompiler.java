package dev.itsdropper.caustiq.shaderpack;

import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles actual Iris full-screen vertex/fragment stages into OpenGL programs. */
public final class IrisGlProgramCompiler {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version[^\\r\\n]*\\R?");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^\\s*#\\s*extension[^\\r\\n]*\\R?");
    private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*uniform\\s+([iu]?sampler\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern IMAGE = Pattern.compile("(?m)^\\s*(?:readonly\\s+|writeonly\\s+)?uniform\\s+[iu]?image[123]D\\s+(\\w+)\\s*;");
    private static final Pattern LOCAL_SIZE = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
    private static final Pattern TEXCOORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*(\\d+)\\s*]");
    private static final String FULLSCREEN_VERTEX = """
            #version 430 core
            out vec2 texCoord;
            out vec2 texcoord;
            out vec2 vTexCoord;
            out vec2 uv;
            const vec2 caustiqPositions[3] = vec2[3](
                vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            const vec2 caustiqCoordinates[3] = vec2[3](
                vec2(0.0, 0.0), vec2(2.0, 0.0), vec2(0.0, 2.0));
            void main() {
                vec2 p = caustiqPositions[gl_VertexID];
                vec2 t = caustiqCoordinates[gl_VertexID];
                gl_Position = vec4(p, 0.0, 1.0);
                texCoord = t;
                texcoord = t;
                vTexCoord = t;
                uv = t;
            }
            """;

    public IrisGlProgram compile(ShaderPackProgramCatalog.Program program,
                                 GlslPreprocessor.SourceProvider sourceProvider,
                                 Set<String> defines) throws IOException {
        if (program.stages().containsKey(ShaderPackProgramCatalog.Stage.GEOMETRY)
                || program.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_CONTROL)
                || program.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_EVALUATION)) {
            throw new IOException("Program '" + program.name() + "' contains an unsupported geometry/tessellation stage.");
        }
        Set<String> compilationDefines = runtimeDefines(defines);
        GlslPreprocessor preprocessor = new GlslPreprocessor();
        if (program.hasCompute()) {
            if (program.stages().size() != 1) {
                throw new IOException("Compute program '" + program.name() + "' must contain only a .csh stage.");
            }
            String compute = preprocessor.preprocess(
                    program.stages().get(ShaderPackProgramCatalog.Stage.COMPUTE), sourceProvider).source();
            compute = normalizeComputeVersion(compute);
            compute = injectDefines(removePackOptionDefinitions(compute, compilationDefines), compilationDefines);
            int shader = compileShader(GL43C.GL_COMPUTE_SHADER, compute, program.name() + " compute");
            int linked = 0;
            try {
                linked = GL20C.glCreateProgram();
                GL20C.glAttachShader(linked, shader);
                GL20C.glLinkProgram(linked);
                if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                    throw new IOException("Iris compute program '" + program.name() + "' failed to link:\\n"
                            + GL20C.glGetProgramInfoLog(linked));
                }
                int[] local = localSizes(compute);
                boolean vectorGlobalInvocation = Pattern.compile(
                        "\\bgl_GlobalInvocationID\\b(?!\\s*\\.[xyz])").matcher(compute).find();
                return new IrisGlProgram(program.name(), linked, findSamplers(compute),
                        findComparisonSamplers(compute), findImages(compute),
                        true, local[0], local[1], local[2],
                        vectorGlobalInvocation || compute.contains("gl_GlobalInvocationID.y"),
                        vectorGlobalInvocation || compute.contains("gl_GlobalInvocationID.z"));
            } catch (IOException | RuntimeException exception) {
                if (linked != 0) GL20C.glDeleteProgram(linked);
                throw exception;
            } finally {
                GL20C.glDeleteShader(shader);
            }
        }
        if (program.fragmentSource() == null || program.vertexSource() != null
                && program.stages().containsKey(ShaderPackProgramCatalog.Stage.COMPUTE)) {
            throw new IOException("Program '" + program.name() + "' is not a supported full-screen vertex/fragment pass.");
        }
        String fragment = preprocessor.preprocess(program.fragmentSource(), sourceProvider).source();
        String vertex;
        if (program.vertexSource() == null) {
            vertex = FULLSCREEN_VERTEX;
        } else {
            String vertexSource = preprocessor.preprocess(program.vertexSource(), sourceProvider).source();
            vertex = translateVertex(vertexSource, texCoordIndices(fragment));
        }
        fragment = translateFragment(fragment);
        vertex = injectDefines(removePackOptionDefinitions(vertex, compilationDefines), compilationDefines);
        fragment = injectDefines(removePackOptionDefinitions(fragment, compilationDefines), compilationDefines);

        int vertexShader = compileShader(GL20C.GL_VERTEX_SHADER, vertex, program.name() + " vertex");
        int fragmentShader = 0;
        int linkedProgram = 0;
        try {
            fragmentShader = compileShader(GL20C.GL_FRAGMENT_SHADER, fragment, program.name() + " fragment");
            linkedProgram = GL20C.glCreateProgram();
            GL20C.glAttachShader(linkedProgram, vertexShader);
            GL20C.glAttachShader(linkedProgram, fragmentShader);
            GL20C.glLinkProgram(linkedProgram);
            if (GL20C.glGetProgrami(linkedProgram, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                throw new IOException("Iris program '" + program.name() + "' failed to link:\n"
                        + GL20C.glGetProgramInfoLog(linkedProgram));
            }
            return new IrisGlProgram(program.name(), linkedProgram, findSamplers(fragment),
                    findComparisonSamplers(fragment), Set.of(), false, 1, 1, 1, false, false);
        } catch (IOException | RuntimeException exception) {
            if (linkedProgram != 0) GL20C.glDeleteProgram(linkedProgram);
            throw exception;
        } finally {
            GL20C.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL20C.glDeleteShader(fragmentShader);
        }
    }

    private static int compileShader(int type, String source, String label) throws IOException {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IOException(label + " failed to compile:\n" + log);
        }
        return shader;
    }

    static Set<String> findSamplers(String source) throws IOException {
        Set<String> samplers = new LinkedHashSet<>();
        Matcher matcher = SAMPLER.matcher(source);
        while (matcher.find()) {
            String samplerType = matcher.group(1);
            String name = matcher.group(2);
            if (!samplerType.equals("sampler2D") && !samplerType.equals("sampler2DShadow")
                    && !samplerType.equals("sampler3D") && !samplerType.equals("usampler3D")) {
                throw new IOException("Iris sampler '" + name + "' uses unsupported type " + samplerType
                        + "; this executor supports sampler2D, sampler2DShadow, sampler3D and usampler3D.");
            }
            samplers.add(name);
        }
        String withoutSupported = SAMPLER.matcher(source).replaceAll("");
        if (Pattern.compile("\\buniform\\s+\\w*sampler\\w*\\b").matcher(withoutSupported).find()) {
            throw new IOException("Iris pass contains a sampler declaration with an unsupported sampler type.");
        }
        return Set.copyOf(samplers);
    }

    static Set<String> findComparisonSamplers(String source) {
        Set<String> samplers = new LinkedHashSet<>();
        Matcher matcher = SAMPLER.matcher(source);
        while (matcher.find()) {
            if (matcher.group(1).equals("sampler2DShadow")) samplers.add(matcher.group(2));
        }
        return Set.copyOf(samplers);
    }

    private static Set<String> findImages(String source) {
        Set<String> images = new LinkedHashSet<>();
        Matcher matcher = IMAGE.matcher(source);
        while (matcher.find()) images.add(matcher.group(1));
        return Set.copyOf(images);
    }

    private static int[] localSizes(String source) {
        int[] sizes = {1, 1, 1};
        Matcher matcher = LOCAL_SIZE.matcher(source);
        while (matcher.find()) {
            int index = switch (matcher.group(1)) { case "x" -> 0; case "y" -> 1; default -> 2; };
            sizes[index] = Integer.parseInt(matcher.group(2));
        }
        return sizes;
    }

    private static String translateVertex(String source, Set<Integer> fragmentTexCoords) throws IOException {
        String working = VERSION.matcher(source).replaceAll("");
        Set<Integer> texCoords = new LinkedHashSet<>(texCoordIndices(working));
        texCoords.addAll(fragmentTexCoords);
        StringBuilder declarations = new StringBuilder();
        declarations.append("const vec2 caustiqPositions[3] = vec2[3](")
                .append("vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));\n")
                .append("const vec2 caustiqCoordinates[3] = vec2[3](")
                .append("vec2(0.0, 0.0), vec2(2.0, 0.0), vec2(0.0, 2.0));\n");
        for (int index : texCoords) {
            if (!Pattern.compile("(?m)^\\s*out\\s+vec4\\s+caustiqTexCoord" + index + "\\s*;").matcher(working).find()
                    && !Pattern.compile("(?m)^\\s*varying\\s+vec4\\s+caustiqTexCoord" + index + "\\s*;").matcher(working).find()) {
                declarations.append("out vec4 caustiqTexCoord").append(index).append(";\n");
            }
        }

        working = working.replaceAll("\\bvarying\\b", "out")
                .replaceAll("\\battribute\\b", "in");
        working = replaceIdentifier(working, "gl_Vertex",
                "vec4(caustiqPositions[gl_VertexID], 0.0, 1.0)");
        working = replaceIdentifier(working, "gl_MultiTexCoord0",
                "vec4(caustiqCoordinates[gl_VertexID], 0.0, 1.0)");
        working = working.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                "vec4(caustiqPositions[gl_VertexID], 0.0, 1.0)");
        for (int index : texCoords) {
            working = working.replaceAll("\\bgl_TexCoord\\s*\\[\\s*" + index + "\\s*]",
                    "caustiqTexCoord" + index);
        }
        if (working.contains("gl_TexCoord") || working.contains("gl_MultiTexCoord")
                || working.contains("gl_Color") || working.contains("gl_Normal")
                || working.contains("gl_FrontColor")) {
            throw new IOException("Iris full-screen vertex shader uses an unsupported legacy vertex input.");
        }
        working = EXTENSION.matcher(working).replaceAll("");
        String translated = "#version 460 core\n" + declarations + working;
        return initializeTexCoordOutputs(translated, texCoords);
    }

    private static String initializeTexCoordOutputs(String source, Set<Integer> indices) {
        if (indices.isEmpty()) return source;
        int main = source.indexOf("void main()");
        if (main < 0) return source;
        int open = source.indexOf('{', main);
        if (open < 0) return source;
        StringBuilder init = new StringBuilder();
        for (int index : indices) {
            init.append("    caustiqTexCoord").append(index)
                    .append(" = vec4(caustiqCoordinates[gl_VertexID], 0.0, 1.0);\n");
        }
        return source.substring(0, open + 1) + "\n" + init + source.substring(open + 1);
    }

    private static String translateFragment(String source) throws IOException {
        String working = VERSION.matcher(source).replaceAll("");
        Set<Integer> texCoords = texCoordIndices(working);
        Set<Integer> fragData = fragmentDataIndices(working);
        boolean usesFragColor = working.contains("gl_FragColor");
        boolean explicitOutput = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+").matcher(working).find();
        if (usesFragColor && explicitOutput) {
            throw new IOException("Iris fragment stage mixes gl_FragColor with explicit output declarations.");
        }
        if (!fragData.isEmpty() && explicitOutput) {
            throw new IOException("Iris fragment stage mixes gl_FragData with explicit output declarations.");
        }

        StringBuilder declarations = new StringBuilder();
        if (usesFragColor) declarations.append("layout(location = 0) out vec4 caustiqFragColor;\n");
        for (int index : fragData) {
            if (index > 31) throw new IOException("Iris MRT location " + index + " exceeds CAUSTIQ's bounded colortex0-colortex31 range.");
            declarations.append("layout(location = ").append(index)
                    .append(") out vec4 caustiqFragData").append(index).append(";\n");
        }
        for (int index : texCoords) {
            if (!Pattern.compile("(?m)^\\s*in\\s+vec4\\s+caustiqTexCoord" + index + "\\s*;").matcher(working).find()
                    && !Pattern.compile("(?m)^\\s*varying\\s+vec4\\s+caustiqTexCoord" + index + "\\s*;").matcher(working).find()) {
                declarations.append("in vec4 caustiqTexCoord").append(index).append(";\n");
            }
        }

        working = working.replaceAll("\\bvarying\\b", "in");
        if (usesFragColor) working = replaceIdentifier(working, "gl_FragColor", "caustiqFragColor");
        for (int index : fragData) {
            working = working.replaceAll("\\bgl_FragData\\s*\\[\\s*" + index + "\\s*]",
                    "caustiqFragData" + index);
        }
        for (int index : texCoords) {
            working = working.replaceAll("\\bgl_TexCoord\\s*\\[\\s*" + index + "\\s*]",
                    "caustiqTexCoord" + index);
        }
        working = working.replaceAll("\\btexture2D\\b|\\btextureCube\\b", "texture")
                .replaceAll("\\btexture2DLod\\b", "textureLod")
                .replaceAll("\\btexture2DProj\\b", "textureProj")
                .replaceAll("\\btexture2DGradARB\\b", "textureGrad");
        if (working.contains("gl_FragData") || working.contains("gl_TexCoord")
                || working.contains("gl_FragColor") || working.contains("gl_FragDepth")) {
            throw new IOException("Iris fragment shader contains an unsupported legacy fragment output.");
        }
        working = EXTENSION.matcher(working).replaceAll("");
        return "#version 460 core\n" + declarations + working;
    }

    private static Set<Integer> texCoordIndices(String source) throws IOException {
        Set<Integer> indices = new LinkedHashSet<>();
        Matcher matcher = TEXCOORD.matcher(source);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index > 31) throw new IOException("Iris gl_TexCoord index " + index + " exceeds the supported range 0-7.");
            indices.add(index);
        }
        return indices;
    }

    private static Set<Integer> fragmentDataIndices(String source) throws IOException {
        Set<Integer> indices = new LinkedHashSet<>();
        Matcher matcher = FRAG_DATA.matcher(source);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index > 31) throw new IOException("Iris MRT location " + index + " exceeds Nvidium's eight draw buffers.");
            indices.add(index);
        }
        return indices;
    }

    private static String removePackOptionDefinitions(String source, Set<String> defines) {
        if (defines == null || defines.isEmpty()) return source;
        String adjusted = source;
        for (String define : defines) {
            if (define == null || define.isBlank()) continue;
            String name = define.split("=", 2)[0].trim();
            String pattern = "(?m)^[\\t ]*#[\\t ]*(?:define|undef)[\\t ]+"
                    + Pattern.quote(name) + "(?:[\\t ]+[^\\r\\n]*)?[\\t ]*(?:\\R|$)";
            adjusted = adjusted.replaceAll(pattern, "");
        }
        return adjusted;
    }

    static Set<String> runtimeDefines(Set<String> packDefines) {
        Set<String> result = new LinkedHashSet<>(packDefines == null ? Set.of() : packDefines);
        boolean handDepthProvided = result.stream().anyMatch(value ->
                value != null && value.split("=", 2)[0].trim().equals("MC_HAND_DEPTH"));
        if (!handDepthProvided) result.add("MC_HAND_DEPTH=1.0");
        // Iris StandardMacros supplies these quality macros to every shader stage.
        // CAUSTIQ compiles outside Iris, so mirror Iris standard defaults.
        result.add("MC_RENDER_QUALITY=1.0");
        result.add("MC_SHADOW_QUALITY=1.0");
        return Set.copyOf(result);
    }

    private static String normalizeComputeVersion(String source) {
        Pattern version = Pattern.compile("(?m)^\\\\s*#\\\\s*version\\\\s+(\\\\d+)(?:\\\\s+(?:core|compatibility))?\\\\s*$");
        Matcher matcher = version.matcher(source);
        if (!matcher.find()) return source;
        int declaredVersion = Integer.parseInt(matcher.group(1));
        if (declaredVersion <= 430) {
            return matcher.replaceFirst("#version " + declaredVersion + " core");
        }
        return matcher.replaceFirst("#version 430 core");
    }

    private static String injectDefines(String source, Set<String> defines) {
        if (defines == null || defines.isEmpty()) return source;
        int newline = source.indexOf('\n');
        StringBuilder block = new StringBuilder();
        for (String define : defines) {
            if (define == null || define.isBlank()) continue;
            String[] parts = define.split("=", 2);
            String name = parts[0].trim();
            String value = parts.length == 2 ? parts[1].trim() : "1";
            if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off") || value.equalsIgnoreCase("no")) continue;
            if (value.equalsIgnoreCase("true")) value = "1";
            block.append("#define ").append(name).append(' ').append(value).append('\n');
        }
        return newline < 0 ? source + "\n" + block : source.substring(0, newline + 1) + block + source.substring(newline + 1);
    }

    private static String replaceIdentifier(String source, String identifier, String replacement) {
        return source.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(identifier)
                + "(?![A-Za-z0-9_])", Matcher.quoteReplacement(replacement));
    }
}
