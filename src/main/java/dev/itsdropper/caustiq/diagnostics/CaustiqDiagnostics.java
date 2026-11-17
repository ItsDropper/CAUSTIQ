package dev.itsdropper.caustiq.diagnostics;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.RendererEnvironment;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumCompatibility;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

import java.util.List;

/** Builds a privacy-conscious report that can be attached to Caustiq bug reports. */
public final class CaustiqDiagnostics {
    private static final int MAX_REPORTED_PACKS = 50;
    private static final int MAX_REPORTED_REJECTIONS = 25;

    private CaustiqDiagnostics() { }

    public static String createReport() {
        FabricLoader loader = FabricLoader.getInstance();
        RendererEnvironment renderer = RendererEnvironment.detect(loader::isModLoaded);
        NvidiumCompatibility.Status compatibility =
                NvidiumCompatibility.inspect(loader::isModLoaded);
        CaustiqConfig config = CaustiqConfig.get();
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        String version = loader.getModContainer(CaustiqClient.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String selectedPack = scan == null ? "Not available" : scan.packs().stream()
                .filter(pack -> pack.id().equals(config.selectedPackId()))
                .map(ShaderPackDescriptor::displayName)
                .findFirst().orElse("None");
        List<String> packNames = scan == null ? List.of() : scan.packs().stream()
                .limit(MAX_REPORTED_PACKS)
                .map(ShaderPackDescriptor::displayName)
                .toList();
        List<RejectedCandidate> rejectedCandidates = scan == null ? List.of() : scan.issues().stream()
                .limit(MAX_REPORTED_REJECTIONS)
                .map(issue -> new RejectedCandidate(issue.displayName(), issue.displayReason()))
                .toList();

        return format(new Snapshot(
                version,
                SharedConstants.getCurrentVersion().name(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                renderer.stack().name(),
                renderer.sodiumLoaded(),
                renderer.nvidiumLoaded(),
                compatibility.name(),
                config.enabled(),
                config.compileBudget(),
                selectedPack,
                scan != null,
                scan == null ? 0 : scan.candidatesInspected(),
                scan == null ? 0 : scan.packs().size(),
                scan == null ? 0 : scan.issues().size(),
                packNames,
                rejectedCandidates,
                scan == null ? 0 : Math.max(0, scan.packs().size() - packNames.size()),
                scan == null ? 0 : Math.max(0, scan.issues().size() - rejectedCandidates.size())
        ));
    }

    static String format(Snapshot snapshot) {
        StringBuilder report = new StringBuilder(768);
        report.append("CAUSTIQ Diagnostics\n")
                .append("CAUSTIQ: ").append(snapshot.caustiqVersion()).append('\n')
                .append("Minecraft: ").append(snapshot.minecraftVersion()).append('\n')
                .append("Java: ").append(snapshot.javaVersion()).append('\n')
                .append("OS: ").append(snapshot.operatingSystem()).append('\n')
                .append("Renderer stack: ").append(snapshot.rendererStack()).append('\n')
                .append("Sodium loaded: ").append(snapshot.sodiumLoaded()).append('\n')
                .append("Nvidium loaded: ").append(snapshot.nvidiumLoaded()).append('\n')
                .append("Nvidium dependency check: ").append(snapshot.nvidiumCompatibility()).append('\n')
                .append("Shaders enabled: ").append(snapshot.shadersEnabled()).append('\n')
                .append("Compile budget: ").append(snapshot.compileBudget()).append(" programs/frame\n")
                .append("Selected shader pack: ").append(snapshot.selectedPack()).append('\n');

        if (!snapshot.scanCompleted()) {
            report.append("Shader-pack scan: not completed\n");
        } else {
            report.append("Shader-pack scan: ").append(snapshot.packCount()).append(" valid pack(s), ")
                    .append(snapshot.rejectedCount()).append(" rejected candidate(s) across ")
                    .append(snapshot.candidateCount()).append(" inspected candidate(s)\n");
            if (snapshot.packNames().isEmpty()) {
                report.append("Discovered packs: none\n");
            } else {
                report.append("Discovered packs:\n");
                snapshot.packNames().forEach(name -> report.append("- ").append(name).append('\n'));
                if (snapshot.omittedPackCount() > 0) {
                    report.append("- ... ").append(snapshot.omittedPackCount())
                            .append(" additional pack name(s) omitted\n");
                }
            }
            if (snapshot.rejectedCandidates().isEmpty()) {
                report.append("Rejected candidates: none\n");
            } else {
                report.append("Rejected candidates (candidate names and reasons; full paths omitted):\n");
                snapshot.rejectedCandidates().forEach(candidate -> report.append("- ")
                        .append(candidate.name()).append(": ").append(candidate.reason()).append('\n'));
                if (snapshot.omittedRejectedCount() > 0) {
                    report.append("- ... ").append(snapshot.omittedRejectedCount())
                            .append(" additional rejection(s) omitted\n");
                }
            }
        }
        report.append("\nNo absolute game-directory paths or shader source contents are included. ")
                .append("Candidate filenames and sanitized rejection reasons are included.");
        return report.toString();
    }

    record RejectedCandidate(String name, String reason) {
        RejectedCandidate {
            name = ShaderPackIssue.sanitizeDisplayText(name);
            reason = ShaderPackIssue.sanitizeDisplayText(reason);
        }
    }

    record Snapshot(
            String caustiqVersion,
            String minecraftVersion,
            String javaVersion,
            String operatingSystem,
            String rendererStack,
            boolean sodiumLoaded,
            boolean nvidiumLoaded,
            String nvidiumCompatibility,
            boolean shadersEnabled,
            int compileBudget,
            String selectedPack,
            boolean scanCompleted,
            int candidateCount,
            int packCount,
            int rejectedCount,
            List<String> packNames,
            List<RejectedCandidate> rejectedCandidates,
            int omittedPackCount,
            int omittedRejectedCount
    ) {
        Snapshot {
            packNames = List.copyOf(packNames);
            rejectedCandidates = List.copyOf(rejectedCandidates);
            if (candidateCount < 0 || packCount < 0 || rejectedCount < 0
                    || omittedPackCount < 0 || omittedRejectedCount < 0) {
                throw new IllegalArgumentException("Diagnostic scan counts must not be negative");
            }
        }
    }
}
