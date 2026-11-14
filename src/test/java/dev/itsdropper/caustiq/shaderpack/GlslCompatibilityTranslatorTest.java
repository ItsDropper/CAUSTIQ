package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class GlslCompatibilityTranslatorTest {
    private final GlslCompatibilityTranslator translator = new GlslCompatibilityTranslator();

    @Test
    void upgradesCommonGlsl120FragmentSyntaxToCore150() throws IOException {
        String source = """
                #version 120
                #extension GL_ARB_shader_texture_lod : enable
                varying vec2 texcoord;
                uniform sampler2D texture;
                void main() {
                    gl_FragColor = texture2D(texture, gl_TexCoord[0].st);
                }
                """;

        String translated = translator.translateLegacyFragment(source);

        assertTrue(translated.startsWith("#version 150"));
        assertTrue(translated.contains("#extension GL_ARB_shader_texture_lod : enable"));
        assertTrue(translated.contains("in vec2 texcoord;"));
        assertTrue(translated.contains("texture(texture, texCoord.st)"));
        assertTrue(translated.contains("out vec4 caustiq_FragColor;"));
        assertTrue(translated.contains("caustiq_FragColor ="));
        assertFalse(translated.contains("gl_FragColor"));
        assertFalse(translated.contains("texture2D("));
    }

    @Test
    void insertsCoreVersionWhenLegacyPackOmitsVersion() throws IOException {
        String translated = translator.translateLegacyFragment(
                "void main() { gl_FragColor = vec4(1.0); }");

        assertTrue(translated.startsWith("#version 150"));
        assertTrue(translated.contains("out vec4 caustiq_FragColor;"));
    }

    @Test
    void leavesModernGlslUntouched() throws IOException {
        String source = "#version 330 core\nout vec4 color;\nvoid main() { color = vec4(1.0); }";
        assertEquals(source, translator.translateLegacyFragment(source));
    }

    @Test
    void rejectsMultipleRenderTargetOutputInsteadOfSilentlyCorruptingIt() {
        IOException exception = assertThrows(IOException.class, () ->
                translator.translateLegacyFragment("#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }"));
        assertTrue(exception.getMessage().contains("multiple-render-target"));
    }
}
