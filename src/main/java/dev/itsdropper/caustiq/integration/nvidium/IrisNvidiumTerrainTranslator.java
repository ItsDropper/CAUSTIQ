package dev.itsdropper.caustiq.integration.nvidium;

import dev.itsdropper.caustiq.shaderpack.IrisShaderPackModel;
import dev.itsdropper.caustiq.shaderpack.ShaderPackProgramCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates an Iris gbuffers_terrain vertex/fragment pair into Nvidium's
 * task/mesh/fragment pipeline. The vertex main is executed once for each
 * emitted Nvidium mesh vertex; Iris varyings are appended to Nvidium's
 * interpolant block and fragment outputs are routed to MRT locations.
 *
 * <p>Unsupported inputs are rejected before shader compilation. This class
 * never substitutes guessed values for an unavailable shader-pack resource.</p>
 */
public final class IrisNvidiumTerrainTranslator {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version[^\\r\\n]*\\R?");
    private static final Pattern PRAGMA = Pattern.compile("(?m)^\\s*#\\s*pragma[^\\r\\n]*\\R?");
    private static final Pattern UNIFORM = Pattern.compile(
            "(?m)^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:attribute|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern VERTEX_OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:(flat|smooth|noperspective)\\s+)?(?:varying|out)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern FRAGMENT_INPUT = Pattern.compile(
            "(?m)^\\s*(?:(flat|smooth|noperspective)\\s+)?(?:varying|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern FRAGMENT_OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?out\\s+(\\w+)\\s+(\\w+)\\s*;\\s*");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)");
    private static final Pattern TEXTURE_FUNCTION = Pattern.compile("\\b(texture2DProj|texture2DLod|texture2DGradARB|texture2D|textureCube)\\b");
    private static final Pattern FRAG_DATA_INDEX = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
    private static final Set<String> SUPPORTED_ATTRIBUTES = Set.of(
            "mc_Entity", "at_midBlock", "mc_midTexCoord", "at_midTexCoord",
            "iris_Normal", "at_tangent", "vaPosition", "vaUV0", "vaUV2", "vaColor",
            "Position", "UV0", "UV2", "Color", "Normal");
    private static final Map<String, String> UNIFORM_ALIASES = Map.ofEntries(
            Map.entry("gtexture", "tex_diffuse"),
            Map.entry("lightmap", "tex_light"),
            Map.entry("gbufferModelView", "caustiqModelView"),
            Map.entry("gbufferModelViewInverse", "caustiqModelViewInverse"),
            Map.entry("gbufferProjection", "caustiqProjection"),
            Map.entry("gbufferProjectionInverse", "caustiqProjectionInverse"),
            Map.entry("cameraPosition", "caustiqCameraPosition"),
            Map.entry("frameTimeCounter", "caustiqFrameTimeCounter"),
            Map.entry("frameCounter", "caustiqFrameCounter"),
            Map.entry("viewSize", "(screenSize * 2.0)"),
            Map.entry("pixelSize", "(1.0 / (screenSize * 2.0))"),
            Map.entry("viewWidth", "(screenSize.x * 2.0)"),
            Map.entry("viewHeight", "(screenSize.y * 2.0)"),
            Map.entry("fogColor", "fogColour"));
    private static final Set<String> NATIVE_INTERPOLANTS = Set.of(
            "uv", "v_colour", "v_vertexColour", "v_lightmapCoord", "v_renderPosition",
            "v_quadMidUv", "v_quadMidPosition", "v_midBlock", "v_blockEntity",
            "v_normal", "v_tangent", "v_tbn", "v_materialFlags",
            "v_irisTexCoord0", "v_irisTexCoord1");

    private final String vertexSource;
    private final String fragmentSource;
    private final List<Varying> varyings;
    private final List<FragmentOutput> outputs;
    private final boolean usesFragData;
    private final int fragDataCount;

    private IrisNvidiumTerrainTranslator(String vertexSource, String fragmentSource) {
        StageSource vertex = prepareVertex(Objects.requireNonNull(vertexSource, "vertexSource"));
        StageSource fragment = prepareFragment(Objects.requireNonNull(fragmentSource, "fragmentSource"));
        this.vertexSource = vertex.source();
        this.fragmentSource = fragment.source();
        this.varyings = matchVaryings(vertex.varyings(), fragment.varyings());
        this.outputs = fragment.outputs();
        this.usesFragData = fragment.usesFragData();
        this.fragDataCount = fragment.fragDataCount();
    }

    public static IrisNvidiumTerrainTranslator from(IrisShaderPackModel model) {
        Objects.requireNonNull(model, "model");
        IrisShaderPackModel.Program program = model.base().programs().get("gbuffers_terrain");
        if (program == null) {
            throw new IllegalArgumentException("The shader pack has no gbuffers_terrain program.");
        }
        if (!program.hasVertexAndFragment() || program.hasGeometryOrTessellation()) {
            throw new IllegalArgumentException("gbuffers_terrain must contain a vertex and fragment stage only.");
        }
        String vertexPath = program.stages().get(ShaderPackProgramCatalog.Stage.VERTEX);
        String fragmentPath = program.stages().get(ShaderPackProgramCatalog.Stage.FRAGMENT);
        String vertex = model.preprocessedSources().get(vertexPath);
        String fragment = model.preprocessedSources().get(fragmentPath);
        if (vertex == null || fragment == null) {
            throw new IllegalArgumentException("gbuffers_terrain references a missing preprocessed shader stage.");
        }
        return new IrisNvidiumTerrainTranslator(vertex, fragment);
    }

    /** Adapts only Nvidium's primary opaque terrain pipeline; other pipelines remain native. */
    public Map<String, String> adapt(Map<String, String> nativeStages) {
        Objects.requireNonNull(nativeStages, "nativeStages");
        if (!NvidiumTerrainVaryingAdapter.isPrimary(nativeStages)) return Map.copyOf(nativeStages);

        String mesh = Objects.requireNonNull(nativeStages.get("MESH"), "MESH");
        String fragment = Objects.requireNonNull(nativeStages.get("FRAGMENT"), "FRAGMENT");
        mesh = injectVertexStage(mesh);
        fragment = injectFragmentStage(fragment);
        Map<String, String> translated = new LinkedHashMap<>(nativeStages);
        translated.put("MESH", mesh);
        translated.put("FRAGMENT", fragment);
        return Map.copyOf(translated);
    }

    private String injectVertexStage(String mesh) {
        String declarations = varyingBlockMembers();
        mesh = insertIntoInterfaceBlock(mesh, "out Interpolants", declarations, "} OUT[];");
        mesh = insertBefore(mesh, "vec4 transformVertex(Vertex V) {", vertexProgramSource());
        String vertexInvocation = vertexInvocation();
        String assignmentAnchor = "    OUT[id].v_materialFlags = V.nvidiumMaterial;";
        if (!mesh.contains(assignmentAnchor)) {
            throw new IllegalStateException("Nvidium primary mesh shader no longer exposes its material-flag assignment.");
        }
        mesh = mesh.replace(assignmentAnchor, assignmentAnchor + "\n" + vertexInvocation);
        return mesh;
    }

    private String injectFragmentStage(String fragment) {
        String declarations = varyingBlockMembers();
        fragment = insertIntoInterfaceBlock(fragment, "in Interpolants", declarations, "};");
        fragment = removeNativeGBufferOutputs(fragment);
        fragment = addPackMrtOutputs(fragment);
        String program = fragmentProgramSource();
        fragment = insertBefore(fragment, "void main() {", program);

        String invocation = fragmentInvocation();
        int close = fragment.lastIndexOf('}');
        if (close < 0 || !fragment.substring(close + 1).isBlank()) {
            throw new IllegalStateException("Nvidium fragment main terminator is not in the expected layout.");
        }
        return fragment.substring(0, close) + invocation + "\n" + fragment.substring(close);
    }

    private String varyingBlockMembers() {
        StringBuilder members = new StringBuilder();
        members.append("    vec4 v_irisTexCoord0;\n    vec4 v_irisTexCoord1;\n");
        for (Varying varying : varyings) {
            if (NATIVE_INTERPOLANTS.contains(varying.name())) {
                throw new IllegalArgumentException("Iris varying collides with Nvidium ABI member: " + varying.name());
            }
            members.append("    ");
            if (varying.qualifier() != null && !varying.qualifier().equals("smooth")) {
                members.append(varying.qualifier()).append(' ');
            }
            members.append(varying.type()).append(' ').append(varying.name()).append(";\n");
        }
        return members.toString();
    }

    private String vertexProgramSource() {
        StringBuilder source = new StringBuilder();
        source.append("Vertex caustiqCurrentVertex;\n")
                .append("uint caustiqCurrentVertexId;\n")
                .append("vec4 caustiqIrisClipPosition;\n")
                .append("vec4 caustiqIrisTexCoord[8];\n");
        for (Varying varying : varyings) {
            source.append(varying.type()).append(" caustiqIrisVarying_")
                    .append(varying.name()).append(";\n");
        }
        String translated = translateVertexBuiltins(vertexSource);
        for (Varying varying : varyings) {
            translated = replaceIdentifier(translated, varying.name(),
                    "caustiqIrisVarying_" + varying.name());
        }
        return source.append(translated).toString();
    }

    private String vertexInvocation() {
        StringBuilder invocation = new StringBuilder();
        invocation.append("    caustiqCurrentVertex = V;\n")
                .append("    caustiqCurrentVertexId = id;\n")
                .append("    caustiqIrisTexCoord[0] = vec4(decodeVertexUV(V), 0.0, 1.0);\n")
                .append("    caustiqIrisTexCoord[1] = vec4(decodeLightUV(V), 0.0, 1.0);\n")
                .append("    caustiqIrisClipPosition = gl_MeshVerticesNV[id].gl_Position;\n");
        for (Varying varying : varyings) {
            invocation.append("    caustiqIrisVarying_").append(varying.name())
                    .append(" = ").append(varying.type()).append("(0);\n");
        }
        invocation.append("    caustiqIrisVertexMain();\n")
                .append("    gl_MeshVerticesNV[id].gl_Position = caustiqIrisClipPosition;\n")
                .append("    OUT[id].uv = caustiqIrisTexCoord[0].xy;\n")
                .append("    OUT[id].v_irisTexCoord0 = caustiqIrisTexCoord[0];\n")
                .append("    OUT[id].v_irisTexCoord1 = caustiqIrisTexCoord[1];\n");
        for (Varying varying : varyings) {
            invocation.append("    OUT[id].").append(varying.name()).append(" = caustiqIrisVarying_")
                    .append(varying.name()).append(";\n");
        }
        return invocation.toString();
    }

    private String fragmentProgramSource() {
        StringBuilder source = new StringBuilder();
        source.append("vec4 caustiqIrisTexCoord[8];\n")
                .append("vec4 caustiqIrisFragData[8];\n");
        String translated = translateFragmentBuiltins(fragmentSource);
        for (FragmentOutput output : outputs) {
            translated = replaceIdentifier(translated, output.name(),
                    output.location() == 0 ? "colour" : "caustiqIrisOutput" + output.location());
        }
        return source.append(translated).toString();
    }

    private String fragmentInvocation() {
        StringBuilder invocation = new StringBuilder();
        invocation.append("    caustiqIrisTexCoord[0] = v_irisTexCoord0;\n")
                .append("    caustiqIrisTexCoord[1] = v_irisTexCoord1;\n");
        if (usesFragData) {
            invocation.append("    for (int caustiqIndex = 0; caustiqIndex < 8; caustiqIndex++) caustiqIrisFragData[caustiqIndex] = vec4(0.0);\n");
        }
        invocation.append("    caustiqIrisFragmentMain();\n");
        if (usesFragData) {
            invocation.append("    colour = caustiqIrisFragData[0];\n");
            for (int index = 1; index < fragDataCount; index++) {
                invocation.append("    caustiqIrisOutput").append(index)
                        .append(" = caustiqIrisFragData[").append(index).append("];\n");
            }
        }
        return invocation.toString();
    }

    private String addPackMrtOutputs(String fragment) {
        Set<Integer> locations = new LinkedHashSet<>();
        for (FragmentOutput output : outputs) {
            if (output.location() > 0) locations.add(output.location());
        }
        if (usesFragData) {
            for (int index = 1; index < fragDataCount; index++) locations.add(index);
        }
        for (int location : locations) {
            fragment = insertAfter(fragment, "layout(location = 0) out vec4 colour;",
                    "layout(location = " + location + ") out vec4 caustiqIrisOutput" + location + ";\n");
        }
        return fragment;
    }

    private static String removeNativeGBufferOutputs(String fragment) {
        fragment = fragment.replaceAll(
                "(?m)^\\s*layout\\(location = [1-4]\\) out vec4 caustiqGBuffer(?:Normal|Position|Material|Lightmap);\\s*\\R?", "");
        fragment = fragment.replaceAll(
                "(?m)^\\s*caustiqGBuffer(?:Normal|Position|Material|Lightmap)\\s*=.*;\\s*\\R?", "");
        return fragment;
    }

    private static StageSource prepareVertex(String source) {
        String clean = stripComments(source);
        List<Varying> varyings = parseVertexVaryings(clean);
        String working = stripStageDirectives(removeUniformsAndAttributes(clean, true));
        return new StageSource(renameMain(working, "caustiqIrisVertexMain"),
                varyings, List.of(), false, 0);
    }

    private static StageSource prepareFragment(String source) {
        String clean = stripComments(source);
        List<Varying> varyings = parseFragmentInputs(clean);
        List<FragmentOutput> outputs = parseFragmentOutputs(clean);
        boolean fragData = clean.contains("gl_FragData");
        int fragDataCount = 0;
        Matcher fragIndices = FRAG_DATA_INDEX.matcher(clean);
        while (fragIndices.find()) fragDataCount = Math.max(fragDataCount, Integer.parseInt(fragIndices.group(1)) + 1);
        if (fragData && !outputs.isEmpty()) {
            throw new IllegalArgumentException("gbuffers_terrain mixes gl_FragData with explicit fragment outputs.");
        }
        if (fragDataCount > 8) {
            throw new IllegalArgumentException("Nvidium MRT supports fragment output locations 0 through 7.");
        }
        String working = stripStageDirectives(removeUniformsAndAttributes(clean, false));
        return new StageSource(renameMain(working, "caustiqIrisFragmentMain"),
                List.of(), outputs, fragData, fragDataCount);
    }

    private static List<Varying> parseVertexVaryings(String source) {
        List<Varying> result = new ArrayList<>();
        Matcher matcher = VERTEX_OUTPUT.matcher(source);
        while (matcher.find()) result.add(new Varying(matcher.group(1), matcher.group(2), matcher.group(3)));
        return List.copyOf(result);
    }

    private static List<Varying> parseFragmentInputs(String source) {
        List<Varying> result = new ArrayList<>();
        Matcher matcher = FRAGMENT_INPUT.matcher(source);
        while (matcher.find()) result.add(new Varying(matcher.group(1), matcher.group(2), matcher.group(3)));
        return List.copyOf(result);
    }

    private static List<FragmentOutput> parseFragmentOutputs(String source) {
        List<FragmentOutput> result = new ArrayList<>();
        Matcher matcher = FRAGMENT_OUTPUT.matcher(source);
        int implicitLocation = 0;
        while (matcher.find()) {
            int location = matcher.group(1) == null ? implicitLocation : Integer.parseInt(matcher.group(1));
            String type = matcher.group(2);
            if (!type.equals("vec4")) {
                throw new IllegalArgumentException("Nvidium terrain MRT currently requires vec4 fragment outputs; got "
                        + type + " at location " + location + ".");
            }
            if (location < 0 || location > 7) {
                throw new IllegalArgumentException("Nvidium MRT supports fragment output locations 0 through 7.");
            }
            result.add(new FragmentOutput(location, type, matcher.group(3)));
            implicitLocation = location + 1;
        }
        return List.copyOf(result);
    }

    private static List<Varying> matchVaryings(List<Varying> vertex, List<Varying> fragment) {
        Map<String, Varying> outputs = new LinkedHashMap<>();
        for (Varying varying : vertex) outputs.put(varying.name(), varying);
        for (Varying input : fragment) {
            Varying output = outputs.get(input.name());
            if (output == null || !output.type().equals(input.type())) {
                throw new IllegalArgumentException("Iris fragment input '" + input.name()
                        + "' has no matching vertex output of type " + input.type() + ".");
            }
        }
        return List.copyOf(outputs.values());
    }

    private static String removeUniformsAndAttributes(String source, boolean vertex) {
        String working = stripComments(source);
        Matcher uniforms = UNIFORM.matcher(working);
        StringBuffer uniformOutput = new StringBuffer();
        while (uniforms.find()) {
            String type = uniforms.group(1);
            String name = uniforms.group(2);
            String expected = expectedUniformType(name);
            if (expected == null) {
                throw new IllegalArgumentException("Unsupported Iris terrain uniform '" + name + "' (" + type + ").");
            }
            if (!expected.equals(type)) {
                throw new IllegalArgumentException("Iris terrain uniform '" + name + "' must be " + expected + ", not " + type + ".");
            }
            if ((name.equals("gtexture") || name.equals("lightmap") || name.equals("viewSize")
                    || name.equals("pixelSize") || name.equals("viewWidth") || name.equals("viewHeight")
                    || name.equals("fogColor")) && vertex) {
                throw new IllegalArgumentException("Iris terrain vertex stage cannot use fragment-only uniform '" + name + "'.");
            }
            uniforms.appendReplacement(uniformOutput, "");
        }
        uniforms.appendTail(uniformOutput);
        working = uniformOutput.toString();

        if (vertex) {
            Matcher attributes = ATTRIBUTE.matcher(working);
            StringBuffer attributeOutput = new StringBuffer();
            while (attributes.find()) {
                String name = attributes.group(2);
                if (!SUPPORTED_ATTRIBUTES.contains(name)) {
                    throw new IllegalArgumentException("Unsupported Iris terrain vertex attribute '" + name + "'.");
                }
                attributes.appendReplacement(attributeOutput, "");
            }
            attributes.appendTail(attributeOutput);
            working = attributeOutput.toString();

            Matcher vertexOutputs = VERTEX_OUTPUT.matcher(working);
            StringBuffer vertexOutputBuffer = new StringBuffer();
            while (vertexOutputs.find()) vertexOutputs.appendReplacement(vertexOutputBuffer, "");
            vertexOutputs.appendTail(vertexOutputBuffer);
            working = vertexOutputBuffer.toString();
            if (Pattern.compile("\\b(attribute|varying)\\b").matcher(working).find()) {
                throw new IllegalArgumentException("Iris terrain vertex source contains an unsupported attribute/varying declaration.");
            }
        } else {
            Matcher inputs = FRAGMENT_INPUT.matcher(working);
            StringBuffer inputOutput = new StringBuffer();
            while (inputs.find()) inputs.appendReplacement(inputOutput, "");
            inputs.appendTail(inputOutput);
            working = inputOutput.toString();

            Matcher outputs = FRAGMENT_OUTPUT.matcher(working);
            StringBuffer outputBuffer = new StringBuffer();
            while (outputs.find()) outputs.appendReplacement(outputBuffer, "");
            outputs.appendTail(outputBuffer);
            working = outputBuffer.toString();
            if (Pattern.compile("\\b(varying|uniform)\\b").matcher(working).find()) {
                throw new IllegalArgumentException("Iris terrain fragment source contains an unsupported varying/uniform declaration.");
            }
        }

        if (Pattern.compile("\\buniform\\b").matcher(working).find()) {
            throw new IllegalArgumentException("Iris terrain stage contains an unsupported uniform declaration.");
        }
        if (Pattern.compile("\\bgl_FragDepth\\b|\\bgl_ClipDistance\\b|\\bgl_PointSize\\b").matcher(working).find()) {
            throw new IllegalArgumentException("Iris terrain stage writes a built-in output not represented by Nvidium's mesh/fragment ABI.");
        }
        return translateLegacyTextureFunctions(working);
    }

    private static String expectedUniformType(String name) {
        return switch (name) {
            case "gtexture", "lightmap" -> "sampler2D";
            case "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse" -> "mat4";
            case "cameraPosition", "fogColor" -> "vec3";
            case "frameTimeCounter", "viewWidth", "viewHeight" -> "float";
            case "frameCounter" -> "int";
            case "viewSize", "pixelSize" -> "vec2";
            default -> null;
        };
    }

    private static String translateVertexBuiltins(String source) {
        String translated = source;
        translated = replaceIdentifier(translated, "gl_VertexID", "int(caustiqCurrentVertexId)");
        translated = replaceIdentifier(translated, "gl_Vertex",
                "(transformMat * vec4(decodeVertexPosition(caustiqCurrentVertex) + origin, 1.0))");
        translated = replaceIdentifier(translated, "gl_MultiTexCoord0",
                "vec4(decodeVertexUV(caustiqCurrentVertex), 0.0, 1.0)");
        translated = replaceIdentifier(translated, "gl_MultiTexCoord1",
                "vec4(decodeLightUV(caustiqCurrentVertex), 0.0, 1.0)");
        translated = replaceIdentifier(translated, "gl_Color", "decodeVertexColour(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "gl_Normal", "caustiqDecodeNormal(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "gl_Position", "caustiqIrisClipPosition");
        translated = replaceIdentifier(translated, "gl_TexCoord", "caustiqIrisTexCoord");
        translated = replaceIdentifier(translated, "mc_Entity", "caustiqDecodeBlockEntity(caustiqCurrentVertex.blockId)");
        translated = replaceIdentifier(translated, "at_midBlock", "caustiqDecodeMidBlock(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "mc_midTexCoord", "caustiqDecodeMidUv(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "at_midTexCoord", "caustiqDecodeMidUv(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "iris_Normal", "caustiqDecodeNormal(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "at_tangent", "caustiqDecodeTangent(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "vaPosition", "decodeVertexPosition(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "vaUV0", "decodeVertexUV(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "vaUV2", "decodeLightUV(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "vaColor", "decodeVertexColour(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "Position", "decodeVertexPosition(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "UV0", "decodeVertexUV(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "UV2", "decodeLightUV(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "Color", "decodeVertexColour(caustiqCurrentVertex)");
        translated = replaceIdentifier(translated, "Normal", "caustiqDecodeNormal(caustiqCurrentVertex)");
        translated = translated.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                "(caustiqProjection * caustiqModelView * (transformMat * vec4(decodeVertexPosition(caustiqCurrentVertex) + origin, 1.0)))");
        return translateUniformAliases(translated);
    }

    private static String translateFragmentBuiltins(String source) {
        String translated = source;
        translated = replaceIdentifier(translated, "gl_TexCoord", "caustiqIrisTexCoord");
        translated = replaceIdentifier(translated, "gl_FragColor", "colour");
        translated = replaceIdentifier(translated, "gl_FragData", "caustiqIrisFragData");
        return translateUniformAliases(translateLegacyTextureFunctions(translated));
    }

    private static String translateUniformAliases(String source) {
        String translated = source;
        for (Map.Entry<String, String> alias : UNIFORM_ALIASES.entrySet()) {
            translated = replaceIdentifier(translated, alias.getKey(), alias.getValue());
        }
        translated = replaceIdentifier(translated, "gl_ModelViewProjectionMatrix",
                "(caustiqProjection * caustiqModelView)");
        translated = replaceIdentifier(translated, "gl_ModelViewMatrixInverse", "caustiqModelViewInverse");
        translated = replaceIdentifier(translated, "gl_ModelViewMatrix", "caustiqModelView");
        translated = replaceIdentifier(translated, "gl_ProjectionMatrixInverse", "caustiqProjectionInverse");
        translated = replaceIdentifier(translated, "gl_ProjectionMatrix", "caustiqProjection");
        translated = replaceIdentifier(translated, "gl_NormalMatrix", "caustiqNormalMatrix");
        return translated;
    }

    private static String replaceIdentifier(String source, String identifier, String replacement) {
        return source.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(identifier)
                + "(?![A-Za-z0-9_])", Matcher.quoteReplacement(replacement));
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//[^\\r\\n]*", "");
    }

    private static String translateLegacyTextureFunctions(String source) {
        Matcher matcher = TEXTURE_FUNCTION.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "texture2D", "textureCube" -> "texture";
                case "texture2DProj" -> "textureProj";
                case "texture2DLod" -> "textureLod";
                case "texture2DGradARB" -> "textureGrad";
                default -> matcher.group(1);
            };
            matcher.appendReplacement(output, replacement);
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String stripStageDirectives(String source) {
        return PRAGMA.matcher(VERSION.matcher(source).replaceAll("")).replaceAll("");
    }

    private static String renameMain(String source, String replacement) {
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Iris terrain stage does not define void main().");
        String result = matcher.replaceFirst(replacement + "()");
        if (MAIN.matcher(result).find()) {
            throw new IllegalArgumentException("Iris terrain stage defines more than one main entry point.");
        }
        return result;
    }

    private static String insertIntoInterfaceBlock(String source, String declaration,
                                                   String members, String terminator) {
        int blockStart = source.indexOf(declaration);
        if (blockStart < 0) throw new IllegalStateException("Nvidium interpolant block is missing: " + declaration);
        int end = source.indexOf(terminator, blockStart);
        if (end < 0) throw new IllegalStateException("Nvidium interpolant block terminator is missing: " + terminator);
        return source.substring(0, end) + members + source.substring(end);
    }

    private static String insertBefore(String source, String anchor, String insertion) {
        int index = source.indexOf(anchor);
        if (index < 0) throw new IllegalStateException("Nvidium shader insertion anchor is missing: " + anchor);
        return source.substring(0, index) + insertion + "\n" + source.substring(index);
    }

    private static String insertAfter(String source, String anchor, String insertion) {
        int index = source.indexOf(anchor);
        if (index < 0) throw new IllegalStateException("Nvidium shader insertion anchor is missing: " + anchor);
        int end = index + anchor.length();
        return source.substring(0, end) + "\n" + insertion + source.substring(end);
    }

    private record Varying(String qualifier, String type, String name) { }
    private record FragmentOutput(int location, String type, String name) { }
    private record StageSource(String source, List<Varying> varyings, List<FragmentOutput> outputs,
                               boolean usesFragData, int fragDataCount) { }
}
