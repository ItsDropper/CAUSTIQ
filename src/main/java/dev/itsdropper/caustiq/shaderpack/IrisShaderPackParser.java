package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parses the parts of the Iris shader-pack contract that can be represented
 * without starting Iris' OpenGL pipeline: properties, dimension program sets,
 * stage inventories, options, feature declarations and GLSL include graphs.
 *
 * <p>This intentionally does not pretend to implement Iris' JCPP preprocessor,
 * directive semantics, custom-uniform expressions, texture lifecycle or render
 * target scheduler. Those remain explicit diagnostics/capability boundaries.</p>
 */
public final class IrisShaderPackParser {
    private static final int MAX_PROPERTIES_BYTES = 1024 * 1024;
    private static final Pattern DIMENSION_DIRECTORY = Pattern.compile("world-?[0-9]+|dh_[a-z0-9_]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRAM_NAME = Pattern.compile(
            "(?:gbuffers_[a-z0-9_]+|shadow(?:comp)?[0-9]*|begin[0-9]*|prepare[0-9]*|setup|" +
                    "deferred[0-9]*|composite[0-9]*|final|dh_[a-z0-9_]+|world)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STAGE_EXTENSIONS = Set.of(".vsh", ".fsh", ".gsh", ".csh", ".tcs", ".tes");

    public IrisShaderPackModel parse(ShaderPackSources sources) throws IOException {
        String propertiesSource = readText(sources, "shaders.properties");
        String dimensionSource = readText(sources, "dimension.properties");
        Map<String, String> properties = parseProperties(propertiesSource, "shaders.properties");
        Map<String, String> dimensionProperties = parseProperties(dimensionSource, "dimension.properties");
        Map<String, String> dimensionMappings = parseDimensionMappings(dimensionProperties);
        Set<String> dimensionDirectories = new LinkedHashSet<>(dimensionMappings.values());

        List<String> paths = sources.listShaderFiles();
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash > 0) {
                String first = path.substring(0, slash);
                if (DIMENSION_DIRECTORY.matcher(first).matches()) dimensionDirectories.add(first);
            }
        }

        Map<String, Map<String, Map<ShaderPackProgramCatalog.Stage, String>>> inventories = new LinkedHashMap<>();
        inventories.put("", new LinkedHashMap<>());
        for (String directory : dimensionDirectories) inventories.put(directory, new LinkedHashMap<>());

        List<String> ignoredStages = new ArrayList<>();
        for (String path : paths) {
            ShaderPackProgramCatalog.Stage stage = stage(path);
            if (stage == null) continue;
            String relative = path;
            String directory = "";
            int slash = path.indexOf('/');
            if (slash > 0) {
                String first = path.substring(0, slash);
                if (dimensionDirectories.contains(first)) {
                    directory = first;
                    relative = path.substring(slash + 1);
                }
            }
            if (relative.indexOf('/') >= 0) continue; // library/include subdirectories are not program entry points
            String filename = relative.substring(relative.lastIndexOf('/') + 1);
            String name = filename.substring(0, filename.length() - stage.extension().length());
            if (!PROGRAM_NAME.matcher(name).matches()) {
                ignoredStages.add(path);
                continue;
            }
            Map<String, Map<ShaderPackProgramCatalog.Stage, String>> programs = inventories.get(directory);
            Map<ShaderPackProgramCatalog.Stage, String> stages =
                    programs.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
            if (stages.putIfAbsent(stage, path) != null) {
                throw new IOException("Iris program '" + name + "' in '" + directory
                        + "' contains duplicate " + stage + " stages.");
            }
        }

        Map<String, String> preprocessed = new LinkedHashMap<>();
        GlslPreprocessor preprocessor = new GlslPreprocessor();
        for (Map<String, Map<ShaderPackProgramCatalog.Stage, String>> programs : inventories.values()) {
            for (Map<ShaderPackProgramCatalog.Stage, String> stages : programs.values()) {
                for (String sourcePath : stages.values()) {
                    GlslPreprocessor.Result result = preprocessor.preprocess(sourcePath, sources);
                    preprocessed.put(sourcePath, result.source());
                }
            }
        }

        Map<String, String> allProperties = new TreeMap<>(properties);
        allProperties.putAll(dimensionProperties);
        ShaderPackMetadata metadata = ShaderPackMetadata.parse(propertiesSource, sources);

        Set<String> required = tokens(properties.get("iris.features.required"));
        Set<String> optional = tokens(properties.get("iris.features.optional"));
        Map<String, String> conditionalPrograms = select(properties, "program.", ".enabled");
        Map<String, String> renderTargets = select(properties, "colortex", null);

        List<String> diagnostics = new ArrayList<>();
        IrisTerrainCompatibilityAnalyzer terrainAnalyzer = new IrisTerrainCompatibilityAnalyzer();
        for (Map.Entry<String, Map<String, Map<ShaderPackProgramCatalog.Stage, String>>> inventory
                : inventories.entrySet()) {
            String scope = inventory.getKey().isEmpty() ? "" : inventory.getKey() + " ";
            for (Map.Entry<String, Map<ShaderPackProgramCatalog.Stage, String>> program
                    : inventory.getValue().entrySet()) {
                String programName = program.getKey();
                if (!isTerrainProgram(programName)) continue;

                Map<ShaderPackProgramCatalog.Stage, String> terrainStages = program.getValue();
                boolean hasVertex = terrainStages.containsKey(ShaderPackProgramCatalog.Stage.VERTEX);
                boolean hasFragment = terrainStages.containsKey(ShaderPackProgramCatalog.Stage.FRAGMENT);
                if (!hasVertex || !hasFragment) {
                    List<String> missing = new ArrayList<>();
                    if (!hasVertex) missing.add("vertex (.vsh)");
                    if (!hasFragment) missing.add("fragment (.fsh)");
                    diagnostics.add("Nvidium " + scope + programName + " ABI: incomplete program; missing "
                            + String.join(" and ", missing) + ".");
                    continue;
                }

                String vertexPath = terrainStages.get(ShaderPackProgramCatalog.Stage.VERTEX);
                String fragmentPath = terrainStages.get(ShaderPackProgramCatalog.Stage.FRAGMENT);
                IrisTerrainCompatibilityAnalyzer.Result terrainCompatibility = terrainAnalyzer.analyze(
                        preprocessed.get(vertexPath), preprocessed.get(fragmentPath));
                terrainCompatibility.issues().forEach(issue ->
                        diagnostics.add("Nvidium " + scope + programName + " ABI: " + issue));
            }
        }
        if (!conditionalPrograms.isEmpty()) {
            diagnostics.add("Conditional program enablement was parsed but Iris option-expression evaluation is not implemented.");
        }
        if (properties.keySet().stream().anyMatch(key -> key.startsWith("uniform.") || key.startsWith("variable."))) {
            diagnostics.add("Custom Iris uniform/variable expressions were detected; they are not evaluated by CAUSTIQ.");
        }
        if (properties.keySet().stream().anyMatch(key -> key.startsWith("texture.") || key.equals("texture.noise"))) {
            diagnostics.add("Custom Iris texture declarations were detected; texture loading and sampler binding are not implemented by the Nvidium bridge.");
        }
        if (!renderTargets.isEmpty()) {
            diagnostics.add("Iris colortex directives were parsed; Nvidium multi-render-target allocation and pass routing are not implemented.");
        }
        if (paths.stream().anyMatch(path -> path.endsWith(".csh"))) {
            diagnostics.add("Compute shader stages were discovered and preprocessed; Nvidium terrain translation does not execute Iris compute passes.");
        }
        if (paths.stream().anyMatch(path -> path.endsWith(".tcs") || path.endsWith(".tes") || path.endsWith(".gsh"))) {
            diagnostics.add("Geometry/tessellation stages were discovered and preprocessed; no equivalent Nvidium terrain-stage translation is registered.");
        }
        if (!ignoredStages.isEmpty()) {
            diagnostics.add("Unrecognized shader stage entry points: " + String.join(", ", ignoredStages));
        }

        Map<String, IrisShaderPackModel.ProgramSet> overrides = new LinkedHashMap<>();
        for (String directory : dimensionDirectories) {
            Map<String, IrisShaderPackModel.Program> programs = freeze(inventories.get(directory));
            if (!programs.isEmpty()) {
                overrides.put(directory, new IrisShaderPackModel.ProgramSet(directory, programs));
            }
        }
        return new IrisShaderPackModel(
                allProperties,
                dimensionMappings,
                required,
                optional,
                conditionalPrograms,
                renderTargets,
                metadata,
                new IrisShaderPackModel.ProgramSet("", freeze(inventories.get(""))),
                overrides,
                preprocessed,
                diagnostics
        );
    }

    private static boolean isTerrainProgram(String name) {
        return name.equals("gbuffers_terrain") || name.startsWith("gbuffers_terrain_");
    }

    private static Map<String, IrisShaderPackModel.Program> freeze(
            Map<String, Map<ShaderPackProgramCatalog.Stage, String>> inventory) {
        Map<String, IrisShaderPackModel.Program> result = new LinkedHashMap<>();
        inventory.forEach((name, stages) -> result.put(name, new IrisShaderPackModel.Program(name, stages)));
        return result;
    }

    private static String readText(ShaderPackSources sources, String path) throws IOException {
        try {
            return sources.read(path);
        } catch (IOException missing) {
            if (missing.getMessage() != null && missing.getMessage().contains("does not exist")) return "";
            throw missing;
        }
    }

    private static Map<String, String> parseProperties(String source, String path) throws IOException {
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_PROPERTIES_BYTES) {
            throw new IOException(path + " exceeds the " + MAX_PROPERTIES_BYTES + " byte parsing limit.");
        }
        Properties parsed = new Properties();
        parsed.load(new StringReader(source));
        Map<String, String> result = new TreeMap<>();
        for (String key : parsed.stringPropertyNames()) result.put(key, parsed.getProperty(key));
        return Map.copyOf(result);
    }

    private static Map<String, String> parseDimensionMappings(Map<String, String> properties) {
        Map<String, String> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (!key.startsWith("dimension.")) return;
            String directory = key.substring("dimension.".length());
            if (!safeSegment(directory)) return;
            for (String dimension : value.split("\\s+")) {
                if (!dimension.isBlank()) result.put(dimension, directory);
            }
        });
        return Map.copyOf(result);
    }

    private static Map<String, String> select(Map<String, String> properties, String prefix, String suffix) {
        Map<String, String> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (!key.startsWith(prefix) || (suffix != null && !key.endsWith(suffix))) return;
            String name = key.substring(prefix.length(), suffix == null ? key.length() : key.length() - suffix.length());
            if (!name.isBlank()) result.put(name, value);
        });
        return Map.copyOf(result);
    }

    private static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.split("[,\\s]+")) {
            String normalized = token.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.matches("[a-z0-9_]+")) result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private static ShaderPackProgramCatalog.Stage stage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : STAGE_EXTENSIONS) {
            if (!lower.endsWith(extension)) continue;
            for (ShaderPackProgramCatalog.Stage stage : ShaderPackProgramCatalog.Stage.values()) {
                if (stage.extension().equals(extension)) return stage;
            }
        }
        return null;
    }

    private static boolean safeSegment(String value) {
        return value != null && !value.isBlank() && !value.equals(".") && !value.equals("..")
                && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
