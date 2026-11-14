package dev.itsdropper.caustiq.shaderpack;

import java.util.List;

/** Immutable result of one shader-pack directory scan. */
public record ShaderPackScanResult(
        List<ShaderPackDescriptor> packs,
        List<ShaderPackIssue> issues,
        int candidatesInspected
) {
    public ShaderPackScanResult {
        packs = List.copyOf(packs);
        issues = List.copyOf(issues);
        if (candidatesInspected < 0) {
            throw new IllegalArgumentException("Candidate count must not be negative");
        }
    }
}
