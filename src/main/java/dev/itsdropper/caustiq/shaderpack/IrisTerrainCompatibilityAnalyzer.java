package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits an Iris gbuffers_terrain pair against the data and render-target
 * contract currently exposed by Nvidium. This is intentionally a rejection
 * gate, not a GLSL transpiler.
 */
public final class IrisTerrainCompatibilityAnalyzer {
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Pattern VERTEX_OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+)?out\\s+\\w+\\s+(\\w+)\\s*(?:\\[[^]]*])?\\s*;");
    private static final Pattern FRAGMENT_INPUT = Pattern.compile(
            "(?m)^\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+)?in\\s+\\w+\\s+(\\w+)\\s*(?:\\[[^]]*])?\\s*;");
    private static final Pattern FRAGMENT_OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?out\\s+\\w+\\s+(\\w+)\\s*;");
    private static final Pattern SAMPLER = Pattern.compile(
            "\\buniform\\s+(?:[iu]?sampler[A-Za-z0-9_]*|image[A-Za-z0-9_]*)\\s+(\\w+)");

    private static final Set<String> UNAVAILABLE_VERTEX_INPUTS = Set.of(
            "mc_Entity", "mc_midTexCoord", "at_midTexCoord", "at_midBlock", "at_tangent",
            "gl_Normal", "gl_MultiTexCoord1");
    private static final Set<String> UNAVAILABLE_TRANSFORMS = Set.of(
            "gl_ModelViewMatrix", "gl_ProjectionMatrix", "gl_TextureMatrix",
            "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection",
            "gbufferProjectionInverse", "cameraPosition");
    private static final Set<String> AVAILABLE_SAMPLERS = Set.of(
            "gtexture", "lightmap", "tex_diffuse", "tex_light");

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

        Set<String> vertexOutputs = declarations(VERTEX_OUTPUT, vertex, 1);
        Set<String> fragmentInputs = declarations(FRAGMENT_INPUT, fragment, 1);
        for (String input : fragmentInputs) {
            if (!vertexOutputs.contains(input) && !Set.of(
                    "uv", "v_colour", "v_vertexColour", "v_lightmapCoord",
                    "v_renderPosition", "v_normal", "v_materialFlags").contains(input)) {
                issues.add(new Issue("UNMAPPED_VARYING", input,
                        "The fragment input has no matching vertex output or Nvidium varying mapping."));
            }
        }
        for (String output : vertexOutputs) {
            if (!fragmentInputs.contains(output) && !Set.of("gl_Position").contains(output)) {
                issues.add(new Issue("UNCONSUMED_VERTEX_OUTPUT", output,
                        "The vertex output is not represented in Nvidium's mesh-to-fragment ABI."));
            }
        }

        Matcher outputs = FRAGMENT_OUTPUT.matcher(fragment);
        int outputCount = 0;
        while (outputs.find()) {
            outputCount++;
            String location = outputs.group(1);
            if (location != null && Integer.parseInt(location) != 0) {
                issues.add(new Issue("MULTIPLE_RENDER_TARGETS", outputs.group(2),
                        "Nvidium's current terrain fragment pipeline does not provide Iris MRT attachment routing."));
            }
        }
        if (outputCount > 1 && issues.stream().noneMatch(i -> i.code().equals("MULTIPLE_RENDER_TARGETS"))) {
            issues.add(new Issue("MULTIPLE_RENDER_TARGETS", Integer.toString(outputCount),
                    "Nvidium's current terrain fragment pipeline does not provide Iris MRT attachment routing."));
        }

        Matcher samplers = SAMPLER.matcher(vertex + "\\n" + fragment);
        while (samplers.find()) {
            String sampler = samplers.group(1);
            if (!AVAILABLE_SAMPLERS.contains(sampler)) {
                issues.add(new Issue("UNBOUND_SAMPLER", sampler,
                        "The sampler has no binding in Nvidium's current terrain shader pipeline."));
            }
        }

        return new Result(issues);
    }

    private static Set<String> declarations(Pattern pattern, String source, int group) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result.add(matcher.group(group));
        return result;
    }

    private static Set<String> identifiers(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(source);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
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
