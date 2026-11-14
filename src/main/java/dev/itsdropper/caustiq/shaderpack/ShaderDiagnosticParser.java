package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses common NVIDIA, Mesa, and generic GLSL compiler log locations. */
public final class ShaderDiagnosticParser {
    private static final Pattern NVIDIA = Pattern.compile(
            "(?m)^\\s*(\\d+)\\((\\d+)\\)\\s*:\\s*(error|warning)(?:\\s+[^:]+)?\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MESA = Pattern.compile(
            "(?m)^\\s*(?:ERROR|WARNING):\\s*(\\d+):(\\d+)(?:\\((\\d+)\\))?:\\s*(?:(error|warning)\\s*:\\s*)?(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENERIC = Pattern.compile(
            "(?m)^\\s*(\\d+):(\\d+)(?:\\((\\d+)\\))?:\\s*(error|warning)\\s*:?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    public List<ShaderDiagnostic> parse(String log, List<GlslPreprocessor.SourceLocation> sourceMap) {
        if (log == null || log.isBlank()) return List.of();
        Map<Integer, String> paths = new HashMap<>();
        for (GlslPreprocessor.SourceLocation location : sourceMap) {
            paths.put(location.sourceId(), location.path());
        }

        List<ShaderDiagnostic> diagnostics = new ArrayList<>();
        parseMatches(NVIDIA.matcher(log), diagnostics, paths, true, false);
        if (diagnostics.isEmpty()) parseMatches(MESA.matcher(log), diagnostics, paths, false, true);
        if (diagnostics.isEmpty()) parseMatches(GENERIC.matcher(log), diagnostics, paths, false, true);
        return List.copyOf(diagnostics);
    }

    private void parseMatches(
            Matcher matcher,
            List<ShaderDiagnostic> output,
            Map<Integer, String> paths,
            boolean nvidia,
            boolean hasColumn
    ) {
        while (matcher.find()) {
            int sourceId = Integer.parseInt(matcher.group(1));
            int line = Integer.parseInt(matcher.group(2));
            int column = hasColumn ? parseOptional(matcher.group(3)) : 0;
            String severityText = nvidia ? matcher.group(3) : matcher.group(4);
            String message = nvidia ? matcher.group(4) : matcher.group(5);
            ShaderDiagnostic.Severity severity = "warning".equalsIgnoreCase(severityText)
                    ? ShaderDiagnostic.Severity.WARNING : ShaderDiagnostic.Severity.ERROR;
            String detail = message == null || message.isBlank()
                    ? "Compiler reported " + severity.name().toLowerCase(java.util.Locale.ROOT)
                    : message.strip();
            output.add(new ShaderDiagnostic(severity, sourceId, line, column, paths.get(sourceId), detail));
        }
    }

    private static int parseOptional(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }
}
