package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts common GLSL 1.10/1.20 fragment syntax used by older OptiFine-style
 * final passes into the GLSL 1.50 core profile used by Minecraft.
 *
 * This is a bounded compatibility layer, not a general GLSL transpiler.
 */
public final class GlslCompatibilityTranslator {
    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)(?:\\s+\\w+)?\\s*$");
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\s+.*$");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[");

    public String translateLegacyFragment(String source) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IOException("Fragment shader source is empty.");
        }

        String[] lines = source.split("\\R", -1);
        int version = 120;
        for (String line : lines) {
            Matcher matcher = VERSION.matcher(line);
            if (matcher.matches()) {
                version = Integer.parseInt(matcher.group(1));
                break;
            }
            if (!line.isBlank() && !line.stripLeading().startsWith("#")
                    && !line.stripLeading().startsWith("//")) {
                break;
            }
        }

        if (version >= 130) return source;
        if (version != 110 && version != 120) {
            throw new IOException("Unsupported legacy GLSL version: " + version);
        }
        if (FRAG_DATA.matcher(source).find()) {
            throw new IOException("Legacy gl_FragData multiple-render-target output is not supported by the current single-target pipeline.");
        }

        List<String> extensions = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (String line : lines) {
            if (VERSION.matcher(line).matches()) continue;
            if (EXTENSION.matcher(line).matches()) extensions.add(line.strip());
            else body.add(line);
        }

        StringBuilder translated = new StringBuilder(source.length() + 128);
        translated.append("#version 150\n");
        for (String extension : extensions) translated.append(extension).append('\n');
        for (String line : body) translated.append(line).append('\n');

        String result = translated.toString()
                .replaceAll("\\btexture2D\\s*\\(", "texture(")
                .replaceAll("\\btextureCube\\s*\\(", "texture(")
                .replaceAll("\\bgl_TexCoord\\s*\\[\\s*0\\s*\\]", "texCoord")
                .replaceAll("\\bvarying\\b", "in")
                .replaceAll("\\bgl_FragColor\\b", "caustiq_FragColor");

        if (result.contains("caustiq_FragColor")) {
            int insertion = result.indexOf('\n') + 1;
            result = result.substring(0, insertion)
                    + "out vec4 caustiq_FragColor;\n" + result.substring(insertion);
        }
        return result;
    }
}
