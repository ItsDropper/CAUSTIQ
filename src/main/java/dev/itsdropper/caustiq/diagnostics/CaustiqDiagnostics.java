package dev.itsdropper.caustiq.diagnostics;

import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.RendererEnvironment;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumCompatibility;
import dev.itsdropper.caustiq.shaderpack.ShaderPackDescriptor;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntimeStatus;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Builds a privacy-conscious report that can be attached to Caustiq bug reports. */
public final class CaustiqDiagnostics {
    private static final int MAX_REPORTED_PACKS = 50;
    private static final int MAX_REPORTED_REJECTIONS = 25;
    static final int MAX_REPORT_CHARACTERS = 16_384;
    private static final String PRIVACY_NOTICE = "\nNo absolute game-directory paths or shader source contents are included. "
            + "Candidate filenames and sanitized rejection reasons are included. Runtime exception messages and compiler output are omitted.";
    private static final String TRUNCATION_NOTICE = "\n[Report truncated; additional entries were omitted to keep it within 16,384 characters.]";

    private CaustiqDiagnostics() { }

    public static String createReport() {
        return format(createSnapshot(), ShaderRuntime.status());
    }

    /** Captures the same sanitized, bounded state used by the shareable report. */
    public static Snapshot createSnapshot() {
        FabricLoader loader = FabricLoader.getInstance();
        RendererEnvironment renderer = RendererEnvironment.detect(loader::isModLoaded);
        NvidiumCompatibility.Status compatibility =
                NvidiumCompatibility.inspect(loader::isModLoaded);
        CaustiqConfig config = CaustiqConfig.get();
        ShaderPackScanResult scan = CaustiqClient.scanResult();
        ShaderPackRefreshStatus refreshStatus = CaustiqClient.refreshStatus();
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

        return new Snapshot(
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
                scan == null ? 0 : Math.max(0, scan.issues().size() - rejectedCandidates.size()),
                refreshStatus.state().name(),
                refreshStatus.failureMessage()
        );
    }

    public enum IssueKind {
        ACCESS,
        INVALID_ARCHIVE,
        PACK_LAYOUT,
        PIPELINE_ENTRY_POINTS,
        UNSAFE_PATH,
        RESOURCE_LIMIT,
        UNSUPPORTED_LINK,
        INVALID_SOURCE,
        OTHER
    }

    /** Maps scanner-owned failure messages to conservative, actionable setup guidance. */
    public static IssueKind classifyIssue(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("symbolic link")) return IssueKind.UNSUPPORTED_LINK;
        if (normalized.contains("invalid zip archive")) return IssueKind.INVALID_ARCHIVE;
        if (normalized.contains("no shaders/ directory")
                || normalized.contains("multiple shaders/ roots")) return IssueKind.PACK_LAYOUT;
        if (normalized.contains("no recognized shader pipeline entry points")) {
            return IssueKind.PIPELINE_ENTRY_POINTS;
        }
        if (containsAny(normalized,
                "absolute entry path", "unsafe entry path", "invalid entry path",
                "duplicate or case-colliding paths", "invalid relative path", "unsafe relative path")) {
            return IssueKind.UNSAFE_PATH;
        }
        if (containsAny(normalized,
                "compressed size", "uncompressed size", "total uncompressed size",
                "compression ratio", "nesting depth", "size overflowed")
                || (normalized.contains("more than ")
                && (normalized.contains(" entries") || normalized.contains(" candidates")))) {
            return IssueKind.RESOURCE_LIMIT;
        }
        if (containsAny(normalized,
                "unable to list shader-pack directory", "does not exist or is not a directory",
                "permission denied", "access denied", "not readable")) {
            return IssueKind.ACCESS;
        }
        if (normalized.contains("candidate is not a regular directory or zip archive")) {
            return IssueKind.INVALID_SOURCE;
        }
        return IssueKind.OTHER;
    }

    /** Creates the path-safe presentation model used by the diagnostics UI. */
    public static IssueDetails describeIssue(ShaderPackIssue issue) {
        Objects.requireNonNull(issue, "issue");
        String candidateName = issue.displayName();
        String reason = issue.displayReason();
        return new IssueDetails(candidateName, reason, classifyIssue(reason));
    }

    public record IssueDetails(String candidateName, String reason, IssueKind kind) {
        public IssueDetails {
            candidateName = ShaderPackIssue.sanitizeDisplayText(candidateName);
            reason = ShaderPackIssue.sanitizeDisplayText(reason);
            kind = kind == null ? IssueKind.OTHER : kind;
        }
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    static String format(Snapshot snapshot) {
        return format(snapshot, null);
    }

    /** Adds a bounded runtime summary without exporting exception messages or compiler output. */
    static String format(Snapshot snapshot, ShaderRuntimeStatus runtimeStatus) {
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
        report.append("Latest shader-pack refresh: ").append(snapshot.latestRefreshState());
        if (!snapshot.latestRefreshFailureMessage().isBlank()) {
            report.append(" - ").append(snapshot.latestRefreshFailureMessage());
        }
        report.append('\n');
        if (runtimeStatus != null) {
            report.append("Shader runtime state: ").append(runtimeStatus.state().name()).append('\n')
                    .append("Runtime selected pack: ").append(runtimeStatus.packName()).append('\n')
                    .append("Runtime programs processed: ").append(runtimeStatus.completedPrograms())
                    .append(" / ").append(runtimeStatus.totalPrograms()).append('\n')
                    .append("Runtime program failures: ").append(runtimeStatus.failedProgramCount()).append('\n');
            if (!runtimeStatus.currentProgram().isBlank()) {
                report.append("Current shader program: ").append(runtimeStatus.currentProgram()).append('\n');
            }
        }
        String body = report.toString();
        String complete = body + PRIVACY_NOTICE;
        if (complete.length() <= MAX_REPORT_CHARACTERS) return complete;

        int bodyLimit = Math.max(0,
                MAX_REPORT_CHARACTERS - PRIVACY_NOTICE.length() - TRUNCATION_NOTICE.length());
        return body.substring(0, Math.min(body.length(), bodyLimit)).stripTrailing()
                + TRUNCATION_NOTICE + PRIVACY_NOTICE;
    }

    public record RejectedCandidate(String name, String reason) {
        public RejectedCandidate {
            name = ShaderPackIssue.sanitizeDisplayText(name);
            reason = ShaderPackIssue.sanitizeDisplayText(reason);
        }
    }

    public record Snapshot(
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
            int omittedRejectedCount,
            String latestRefreshState,
            String latestRefreshFailureMessage
    ) {
        public Snapshot {
            caustiqVersion = ShaderPackIssue.sanitizeDisplayText(caustiqVersion);
            minecraftVersion = ShaderPackIssue.sanitizeDisplayText(minecraftVersion);
            javaVersion = ShaderPackIssue.sanitizeDisplayText(javaVersion);
            operatingSystem = ShaderPackIssue.sanitizeDisplayText(operatingSystem);
            rendererStack = ShaderPackIssue.sanitizeDisplayText(rendererStack);
            nvidiumCompatibility = ShaderPackIssue.sanitizeDisplayText(nvidiumCompatibility);
            selectedPack = ShaderPackIssue.sanitizeDisplayText(selectedPack);
            packNames = packNames == null ? List.of()
                    : packNames.stream().map(ShaderPackIssue::sanitizeDisplayText).toList();
            rejectedCandidates = rejectedCandidates == null ? List.of() : List.copyOf(rejectedCandidates);
            latestRefreshState = latestRefreshState == null || latestRefreshState.isBlank()
                    ? "UNKNOWN" : ShaderPackIssue.sanitizeDisplayText(latestRefreshState);
            latestRefreshFailureMessage = latestRefreshFailureMessage == null
                    || latestRefreshFailureMessage.isBlank()
                    ? "" : ShaderPackIssue.sanitizeDisplayText(latestRefreshFailureMessage);
            if (candidateCount < 0 || packCount < 0 || rejectedCount < 0
                    || omittedPackCount < 0 || omittedRejectedCount < 0) {
                throw new IllegalArgumentException("Diagnostic scan counts must not be negative");
            }
        }
    }
}
