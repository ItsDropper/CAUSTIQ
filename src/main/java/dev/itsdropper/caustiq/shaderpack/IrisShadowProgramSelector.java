package dev.itsdropper.caustiq.shaderpack;

/**
 * Resolves shadow programs from the active Iris program set. Dimension
 * overrides are independent program sets; programs from another dimension
 * must never be used as a fallback.
 */
final class IrisShadowProgramSelector {
    private IrisShadowProgramSelector() { }

    static String select(IrisShaderPackModel model, String dimensionId, String... candidates) {
        IrisShaderPackModel.ProgramSet activeSet = model.base();
        if (dimensionId != null && !dimensionId.isBlank()) {
            String directory = model.dimensionMappings().getOrDefault(
                    dimensionId, builtInDimensionDirectory(dimensionId));
            IrisShaderPackModel.ProgramSet override = model.dimensionOverrides().get(directory);
            if (override != null) activeSet = override;
        }

        for (String candidate : candidates) {
            if (activeSet.programs().containsKey(candidate)) return candidate;
        }
        return null;
    }

    private static String builtInDimensionDirectory(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> "world0";
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> dimensionId;
        };
    }
}
