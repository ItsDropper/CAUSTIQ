package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the common GLSL 1.10/1.20 fragment-shader idioms used by older
 * OptiFine-style final passes into the GLSL 1.50 core profile used by Minecraft.
 *
 * This is deliberately a bounded compatibility layer, not a general GLSL
 * transpiler. Unsupported multi-render-target output is rejected explicitly.
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
        int versionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = VERSION.matcher(lines[i]);
            if (matcher.matches()) {
                version = Integer.parseInt(matcher.group(1));
                versionLine = i;
                break;
            }
            if (!lines[i].isBlank() && !lines[i].stripLeading().startsWith("#")
                    && !lines[i].stripLeading().startsWith("//")) {
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
            if (EXTENSION.matcher(line).matches()) extensions.add(line.strip());
            else body.add(line);
        }

        int insertion = 0;
        if (versionLine >= 0) {
            // The version directive must remain the first directive.
            body.remove(versionLine);
            body.add(0, "#version 150");
            insertion = 1;
        } else {
            body.add(0, "#version 150");
            insertion = 1;
        }
        for (String extension : extensions) body.add(insertion++, extension);

        StringBuilder translated = new StringBuilder(source.length() + 96);
        translated.append(String.join("\n", body));
        String result = translated.toString()
                .replaceAll("\\btexture2D\\s*\\(", "texture(")
                .replaceAll("\\btextureCube\\s*\\(", "texture(")
                .replaceAll("\\bgl_TexCoord\\s*\\[\\s*0\\s*\\]", "texCoord")
                .replaceAll("\\bvarying\\b", "in")
                .replaceAll("\\bgl_FragColor\\b", "caustiq_FragColor");

        if (result.contains("caustiq_FragColor")) {
            declaration = result.indexOf('\n', declaration);
            declaration = result.indexOf('\n', declaration);
            if (declaration < 0) declaration = 0;
            else declaration++;
            result = result.substring(0, declaration)
                    + "out vec4 caustiq_FragColor;\n" + result.substring(declaration);
        }
        return result;
    }
}
