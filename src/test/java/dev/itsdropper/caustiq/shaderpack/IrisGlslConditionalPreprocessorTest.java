package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisGlslConditionalPreprocessorTest {
    private final IrisGlslConditionalPreprocessor preprocessor = new IrisGlslConditionalPreprocessor();

    @Test
    void ignoresConditionalDirectivesInsideMultilineComments() {
        String source = """
                /*
                #if COMMENT_ONLY
                #endif
                */
                #if ENABLED
                float activeBranch;
                #else
                float inactiveBranch;
                #endif
                """;

        String result = preprocessor.preprocess(source, Map.of("ENABLED", "1"));

        assertTrue(result.contains("float activeBranch;"));
        assertFalse(result.contains("float inactiveBranch;"));
        assertTrue(result.contains("#if COMMENT_ONLY"), "Comment text must remain in the emitted source.");
    }

    @Test
    void ignoresAnUnclosedConditionalInsideAComment() {
        String source = """
                /*
                #if COMMENT_ONLY
                */
                #if ENABLED
                float activeBranch;
                #endif
                """;

        String result = assertDoesNotThrow(() -> preprocessor.preprocess(source, Map.of("ENABLED", "1")));

        assertTrue(result.contains("float activeBranch;"));
    }

    @Test
    void stripsCommentsFromDirectiveExpressionsWithoutChangingEmittedLines() {
        String source = """
                #define ENABLED 1 // define comment
                #if ENABLED /* condition comment */
                float activeBranch;
                #endif
                """;

        String result = preprocessor.preprocess(source, Map.of());

        assertTrue(result.contains("#define ENABLED 1 // define comment"));
        assertTrue(result.contains("float activeBranch;"));
    }
}
