package dev.itsdropper.caustiq.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed Iris/OptiFine shader-pack structure. Dimension program sets are kept
 * separate because Iris treats a world override as an independent ProgramSet,
 * not as a per-program fallback overlay.
 */
public record IrisShaderPackModel(
        Map<String, String> properties,
        Map<String, String> dimensionMappings,
        Set<String> requiredFeatures,
        Set<String> optionalFeatures,
        Map<String, String> conditionalPrograms,
        Map<String, String> renderTargetDirectives,
        ShaderPackMetadata options,
        ProgramSet base,
        Map<String, ProgramSet> dimensionOverrides,
        Map<String, String> preprocessedSources,
        List<String> diagnostics
) {
    public IrisShaderPackModel {
        properties = Map.copyOf(properties);
        dimensionMappings = Map.copyOf(dimensionMappings);
        requiredFeatures = Set.copyOf(requiredFeatures);
        optionalFeatures = Set.copyOf(optionalFeatures);
        conditionalPrograms = Map.copyOf(conditionalPrograms);
        renderTargetDirectives = Map.copyOf(renderTargetDirectives);
        dimensionOverrides = Map.copyOf(dimensionOverrides);
        preprocessedSources = Map.copyOf(preprocessedSources);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Resolves the exact Iris ProgramSet for a Minecraft dimension identifier or pack directory. */
    public ProgramSet programSetForDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) return base;
        String directory = dimensionMappings.getOrDefault(dimensionId, builtInDimensionDirectory(dimensionId));
        ProgramSet override = dimensionOverrides.get(directory);
        return override == null ? base : override;
    }

    private static String builtInDimensionDirectory(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> "world0";
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> dimensionId;
        };
    }

    public record Program(String name, Map<ShaderPackProgramCatalog.Stage, String> stages) {
        public Program {
            stages = Map.copyOf(stages);
        }

        public boolean hasVertexAndFragment() {
            return stages.containsKey(ShaderPackProgramCatalog.Stage.VERTEX)
                    && stages.containsKey(ShaderPackProgramCatalog.Stage.FRAGMENT);
        }

        public boolean hasGeometryOrTessellation() {
            return stages.containsKey(ShaderPackProgramCatalog.Stage.GEOMETRY)
                    || stages.containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_CONTROL)
                    || stages.containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_EVALUATION);
        }

        public boolean hasCompute() {
            return stages.containsKey(ShaderPackProgramCatalog.Stage.COMPUTE);
        }
    }

    public record ProgramSet(String directory, Map<String, Program> programs) {
        public ProgramSet {
            directory = directory == null ? "" : directory;
            programs = Map.copyOf(programs);
        }

        public boolean isEmpty() {
            return programs.isEmpty();
        }
    }
}
