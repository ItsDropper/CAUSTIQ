package dev.itsdropper.caustiq.shaderpack;

/**
 * Resolves shadow programs from the active Iris program set. Dimension
 * overrides are independent program sets; programs from another dimension
 * must never be used as a fallback.
 */
final class IrisShadowProgramSelector {
    private IrisShadowProgramSelector() { }

    static String select(IrisShaderPackModel model, String dimensionId, String... candidates) {
        IrisShaderPackModel.ProgramSet activeSet = model.programSetForDimension(dimensionId);

        for (String candidate : candidates) {
            if (activeSet.programs().containsKey(candidate)) return candidate;
        }
        return null;
    }

}
