package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTerrainAbi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits an Iris gbuffers_terrain pair against the data and render-target
 * contract currently exposed by Nvidium. This is a rejection gate, not a GLSL
 * translator: a clean result means the declared interface is representable,
 * not that arbitrary vertex shader logic has been translated.
 */
public final class IrisTerrainCompatibilityAnalyzer {
    private static final String QUALIFIERS =
            "(?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise|highp|mediump|lowp)\\s+)*";
    private static final String LAYOUT = "(?:layout\\s*\\([^)]*\\)\\s*)*";
    private static final Pattern INTERFACE_DECLARATION = Pattern.compile(
            "(?m)^\\s*" + LAYOUT + QUALIFIERS
                    + "(in|out|attribute|varying)\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^]]*])?\\s*;");
    private static final Pattern FRAGMENT_OUTPUT = Pattern.compile(
            "(?m)^\\s*(" + LAYOUT + ")" + QUALIFIERS
                    + "out\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*(\\[[^]]*])?\\s*;");
    private static final Pattern LOCATION = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)");
    private static final Pattern TEXTURE_COORDINATE_INDEX = Pattern.compile(
            "\\bgl_TexCoord\\s*\\[\\s*([0-9]+)\\s*]");
    private static final Pattern DYNAMIC_TEXTURE_COORDINATE_INDEX = Pattern.compile(
            "\\bgl_TexCoord\\s*\\[(?!\\s*[0-9]+\\s*])");
    private static final Pattern FRAGMENT_DATA_INDEX = Pattern.compile(
            "\\bgl_FragData\\s*\\[\\s*([0-9]+)\\s*]");
    private static final Pattern DYNAMIC_FRAGMENT_DATA_INDEX = Pattern.compile(
            "\\bgl_FragData\\s*\\[(?!\\s*[0-9]+\\s*])");
    private static final Pattern SAMPLER = Pattern.compile(
            "\\buniform\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "([iu]?sampler[A-Za-z0-9_]*|image[A-Za-z0-9_]*)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern UNIFORM = Pattern.compile(
            "\\buniform\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*(?:\\[[^]]*])?\\s*;");
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "\\buniform\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\{");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Pattern CONDITIONAL_DIRECTIVE = Pattern.compile(
            "(?m)^\\s*#\\s*(?:if|ifdef|ifndef|elif)\\b");

    private static final Set<String> UNAVAILABLE_VERTEX_INPUTS = Set.of(
            "mc_midBlock", "vaNormal", "gl_MultiTexCoord2", "gl_MultiTexCoord3",
            "gl_MultiTexCoord4", "gl_MultiTexCoord5", "gl_MultiTexCoord6", "gl_MultiTexCoord7",
            "gl_SecondaryColor", "gl_FogCoord", "gl_ClipVertex", "gl_PointSize");
    private static final Set<String> UNAVAILABLE_TRANSFORMS = Set.of(
            "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse");
    private static final Set<String> AVAILABLE_VERTEX_UNIFORMS = Set.of(
            "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection",
            "gbufferProjectionInverse", "gbufferPreviousModelView", "gbufferPreviousModelViewInverse",
            "gbufferPreviousProjection", "gbufferPreviousProjectionInverse", "previousCameraPosition",
            "cameraPosition", "frameTimeCounter", "frameCounter",
            "near", "far", "atlasSize", "rainStrength", "wetness", "taa_offset", "light_dir",
            "world_age", "worldTime", "worldDay", "day_factor", "eyePosition", "time_sunrise", "time_noon", "time_sunset",
            "time_midnight", "biome_temperature", "biome_humidity", "alphaTestRef",
            "blockEntityId", "entityId", "currentRenderedItemId", "entityColor", "view_res", "view_pixel_size");
    private static final Set<String> AVAILABLE_SAMPLERS = Set.of(
            "gtexture", "lightmap", "tex_diffuse", "tex_light", "noisetex");


    public Result analyze(String vertexSource, String fragmentSource) {
        if (vertexSource == null || vertexSource.isBlank()
                || fragmentSource == null || fragmentSource.isBlank()) {
            throw new IllegalArgumentException("Both gbuffers_terrain vertex and fragment sources are required.");
        }

        String vertex = stripComments(vertexSource);
        String fragment = stripComments(fragmentSource);
        Set<String> vertexIdentifiers = identifiers(vertex);
        Set<String> fragmentIdentifiers = identifiers(fragment);
        List<Issue> issues = new ArrayList<>();
        if (CONDITIONAL_DIRECTIVE.matcher(vertex).find()
                || CONDITIONAL_DIRECTIVE.matcher(fragment).find()) {
            issues.add(new Issue("UNRESOLVED_PREPROCESSOR_CONDITION", "GLSL conditional directives",
                    "The compatibility audit expands includes but does not evaluate Iris/JCPP macro conditions; branch-specific interfaces cannot be proven safe."));
        }

        for (String symbol : UNAVAILABLE_VERTEX_INPUTS) {
            if (vertexIdentifiers.contains(symbol)) {
                issues.add(new Issue("UNAVAILABLE_VERTEX_INPUT", symbol,
                        "Nvidium's packed terrain vertex does not preserve this Iris vertex attribute."));
            }
        }
        for (String symbol : UNAVAILABLE_TRANSFORMS) {
            if (vertexIdentifiers.contains(symbol) || fragmentIdentifiers.contains(symbol)) {
                issues.add(new Issue("UNAVAILABLE_TRANSFORM", symbol,
                        "Nvidium does not expose the equivalent Iris transform/camera uniform."));
            }
        }

        Map<String, Declaration> vertexOutputs = declarations(vertex, Set.of("out", "varying"));
        Map<String, Declaration> fragmentInputs = declarations(fragment, Set.of("in", "varying"));
        Map<String, Declaration> vertexAttributes = declarations(vertex, Set.of("attribute", "in"));
        for (Declaration attribute : vertexAttributes.values()) {
            if (UNAVAILABLE_VERTEX_INPUTS.contains(attribute.name())) continue;
            if (NvidiumIrisTerrainAbi.vertexSemantic(attribute.name())
                    .filter(semantic -> semantic.availability() != NvidiumIrisTerrainAbi.Availability.UNAVAILABLE)
                    .isEmpty()) {
                issues.add(new Issue("UNMAPPED_VERTEX_INPUT", attribute.name(),
                        "Nvidium's packed terrain vertex has no source for this declared vertex attribute."));
            }
        }

        for (Declaration input : fragmentInputs.values()) {
            Declaration output = vertexOutputs.get(input.name());
            if (output == null && !NvidiumVaryings.contains(input.name())) {
                issues.add(new Issue("UNMAPPED_VARYING", input.name(),
                        "The fragment input has no matching vertex output or Nvidium varying mapping."));
            } else if (output != null && !output.type().equals(input.type())) {
                issues.add(new Issue("VARYING_TYPE_MISMATCH", input.name(),
                        "Vertex output type " + output.type() + " does not match fragment input type " + input.type() + "."));
            }
        }
        for (Declaration output : vertexOutputs.values()) {
            if (!fragmentInputs.containsKey(output.name()) && !output.name().equals("gl_Position")) {
                issues.add(new Issue("UNCONSUMED_VERTEX_OUTPUT", output.name(),
                        "The vertex output is not represented in Nvidium's mesh-to-fragment ABI."));
            }
        }

        auditFragmentOutputs(fragment, issues);
        auditSamplers(vertex, "vertex", issues);
        auditSamplers(fragment, "fragment", issues);
        auditUniforms(vertex, "vertex", issues);
        auditUniforms(fragment, "fragment", issues);
        if (UNIFORM_BLOCK.matcher(vertex).find() || UNIFORM_BLOCK.matcher(fragment).find()) {
            issues.add(new Issue("UNSUPPORTED_UNIFORM_BLOCK", "uniform block",
                    "Iris uniform blocks require layout and binding integration that the Nvidium terrain bridge does not provide."));
        }

        Matcher textureCoordinates = TEXTURE_COORDINATE_INDEX.matcher(vertex + "\n" + fragment);
        while (textureCoordinates.find()) {
            int index = Integer.parseInt(textureCoordinates.group(1));
            if (index > 1) {
                issues.add(new Issue("UNSUPPORTED_TEXTURE_COORDINATE_SET", "gl_TexCoord[" + index + "]",
                        "Only texture coordinate set 0 (terrain UV) and set 1 (lightmap UV) are represented."));
            }
        }
        if (DYNAMIC_TEXTURE_COORDINATE_INDEX.matcher(vertex + "\\n" + fragment).find()) {
            issues.add(new Issue("DYNAMIC_TEXTURE_COORDINATE_INDEX", "gl_TexCoord",
                    "A dynamic texture-coordinate index cannot be mapped to Nvidium's fixed UV/lightmap attributes."));
        }
        Matcher fragmentData = FRAGMENT_DATA_INDEX.matcher(fragment);
        while (fragmentData.find()) {
            int index = Integer.parseInt(fragmentData.group(1));
            if (index > 7) {
                issues.add(new Issue("MULTIPLE_RENDER_TARGETS", "gl_FragData[" + index + "]",
                        "Nvidium's terrain G-buffer exposes fragment output locations 0 through 7."));
            }
        }
        if (DYNAMIC_FRAGMENT_DATA_INDEX.matcher(fragment).find()) {
            issues.add(new Issue("DYNAMIC_FRAGMENT_OUTPUT_INDEX", "gl_FragData",
                    "A dynamic fragment output index cannot be routed to Nvidium's single color attachment."));
        }
                return new Result(deduplicate(issues));
    }

    private static void auditSamplers(String source, String stage, List<Issue> issues) {
        Matcher samplers = SAMPLER.matcher(source);
        while (samplers.find()) {
            String sampler = samplers.group(2);
            if (!AVAILABLE_SAMPLERS.contains(sampler)) {
                issues.add(new Issue("UNBOUND_SAMPLER", sampler,
                        "The sampler has no binding in Nvidium's current terrain stage ABI."));
            }
        }
    }

    private static void auditUniforms(String source, String stage, List<Issue> issues) {
        Matcher uniforms = UNIFORM.matcher(source);
        while (uniforms.find()) {
            String type = uniforms.group(1);
            String name = uniforms.group(2);
            if (type.startsWith("sampler") || type.startsWith("isampler")
                    || type.startsWith("usampler") || type.startsWith("image")) {
                continue;
            }
            if (UNAVAILABLE_TRANSFORMS.contains(name)
                    || AVAILABLE_VERTEX_UNIFORMS.contains(name)
                    || (stage.equals("fragment") && NvidiumIrisTerrainAbi.fragmentSemantic(name).isPresent())) {
                continue;
            }
            issues.add(new Issue("UNBOUND_UNIFORM", name,
                    "No value or binding for this Iris " + stage + " uniform is exposed by Nvidium's terrain pipeline."));
        }
    }

    private static void auditFragmentOutputs(String source, List<Issue> issues) {
        Matcher matcher = FRAGMENT_OUTPUT.matcher(source);
        int outputCount = 0;
        Set<Integer> locations = new LinkedHashSet<>();
        while (matcher.find()) {
            outputCount++;
            if (matcher.group(4) != null) {
                issues.add(new Issue("UNSUPPORTED_FRAGMENT_OUTPUT_ARRAY", matcher.group(3),
                        "Fragment output arrays require multiple color attachments or indexed routing not exposed by Nvidium."));
            }
            Matcher location = LOCATION.matcher(matcher.group(1));
            if (location.find()) {
                int index = Integer.parseInt(location.group(1));
                locations.add(index);
                if (index > 7) {
                    issues.add(new Issue("MULTIPLE_RENDER_TARGETS", matcher.group(3),
                            "Nvidium's terrain G-buffer exposes color attachment locations 0 through 7; location "
                                    + index + " is unavailable."));
                }
            } else if (outputCount > 1) {
                issues.add(new Issue("MULTIPLE_RENDER_TARGETS", matcher.group(3),
                        "Multiple fragment outputs require explicit attachment routing, which Nvidium does not provide."));
            }
        }
        if (outputCount > 1 && locations.size() <= 1
                && issues.stream().noneMatch(issue -> issue.code().equals("MULTIPLE_RENDER_TARGETS"))) {
            issues.add(new Issue("MULTIPLE_RENDER_TARGETS", Integer.toString(outputCount),
                    "Nvidium's current terrain fragment pipeline does not provide Iris MRT attachment routing."));
        }
    }

    private static Map<String, Declaration> declarations(String source, Set<String> storage) {
        Map<String, Declaration> result = new LinkedHashMap<>();
        Matcher matcher = INTERFACE_DECLARATION.matcher(source);
        while (matcher.find()) {
            String qualifier = matcher.group(1);
            if (!storage.contains(qualifier)) continue;
            result.put(matcher.group(3), new Declaration(matcher.group(2), matcher.group(3), qualifier));
        }
        return result;
    }

    private static Set<String> identifiers(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(source);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static List<Issue> deduplicate(List<Issue> issues) {
        Map<String, Issue> unique = new LinkedHashMap<>();
        for (Issue issue : issues) unique.putIfAbsent(issue.code() + "\\0" + issue.symbol(), issue);
        return List.copyOf(unique.values());
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private record Declaration(String type, String name, String storage) { }

    private static final class NvidiumVaryings {
        private static final Set<String> VALUES = Set.of(
                "uv", "v_colour", "v_vertexColour", "v_lightmapCoord",
                "v_renderPosition", "v_normal", "v_materialFlags");
        private static boolean contains(String name) { return VALUES.contains(name); }
    }

    public record Issue(String code, String symbol, String explanation) {
        @Override public String toString() {
            return code + " (" + symbol + "): " + explanation;
        }
    }

    public record Result(List<Issue> issues) {
        public Result { issues = List.copyOf(issues); }
        public boolean compatible() { return issues.isEmpty(); }
    }
}
