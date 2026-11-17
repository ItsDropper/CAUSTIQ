package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackIssueTest {
    @Test
    void exposesOnlyTheCandidateFileNameAndSanitizesAbsolutePaths() {
        ShaderPackIssue issue = new ShaderPackIssue(
                Path.of("/home/player/.minecraft/shaderpacks/Broken Pack.zip"),
                "Unable to open /home/player/.minecraft/shaderpacks/Broken Pack.zip: permission denied"
        );

        assertEquals("Broken Pack.zip", issue.displayName());
        assertTrue(issue.displayReason().contains("[path]"));
        assertFalse(issue.displayReason().contains("/home/player"));
        assertFalse(issue.displayReason().contains(".minecraft"));
    }

    @Test
    void sanitizesWindowsPathsAndControlCharacters() {
        String result = ShaderPackIssue.sanitizeDisplayText(
                "Access denied at C:\\Users\\player name\\AppData\\Roaming\\.minecraft\\shaderpacks: Permission denied"
        );

        assertTrue(result.startsWith("Access denied at [path]"));
        assertTrue(result.endsWith("Permission denied"));
        assertFalse(result.contains("C:\\Users"));
        assertFalse(result.contains("\n"));
    }

    @Test
    void sanitizesAbsolutePathsThatContainSpaces() {
        String result = ShaderPackIssue.sanitizeDisplayText(
                "Unable to read /home/player name/.minecraft/shaderpacks/Broken Pack.zip: invalid ZIP"
        );

        assertEquals("Unable to read [path]: invalid ZIP", result);
        assertFalse(result.contains("player name"));
        assertFalse(result.contains(".minecraft"));
    }

    @Test
    void boundsLongMessagesForOnScreenPresentation() {
        String result = ShaderPackIssue.sanitizeDisplayText("x".repeat(400));

        assertEquals(240, result.length());
        assertTrue(result.endsWith("..."));
    }
}
