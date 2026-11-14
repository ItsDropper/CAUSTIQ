package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ShaderRuntime {
    private static final AtomicReference<ShaderPackScanResult> SCAN_RESULT = new AtomicReference<>();
    private static final List<CompiledShaderProgram> COMPILED = new ArrayList<>();
    private static final ShaderPostProcessPass POST_PROCESS = new ShaderPostProcessPass();
    private static boolean discoveryStarted, discoveryFinished;
    private static ShaderPackSources sources;
    private static List<ShaderPackProgramCatalog.Program> pending = List.of();
    private static IrisTerrainPassScheduler.Plan activePassPlan;
    private static Map<String, String> selectedDefines = Map.of();
    private static int nextProgram;
    private static boolean postProcessFailureLogged;
    private static long lastReportedNvidiumSolidFrames;
    private static boolean warnedNvidiumBackendNotObserved;

    private ShaderRuntime() { }

    public static void publishScanResult(ShaderPackScanResult result) {
        discoveryStarted = false;
        discoveryFinished = false;
        SCAN_RESULT.set(result);
    }

    /** Invalidates compiled resources after a UI/configuration change. Render-thread only. */
    public static void requestReload() {
        closeSources();
        POST_PROCESS.close();
        COMPILED.forEach(CompiledShaderProgram::close);
        COMPILED.clear();
        pending = List.of();
        activePassPlan = null;
        selectedDefines = Map.of();
        nextProgram = 0;
        discoveryStarted = false;
        discoveryFinished = false;
        postProcessFailureLogged = false;
    }

    public static IrisTerrainPassScheduler.Plan activePassPlan() { return activePassPlan; }

    public static void onWorldRender() {
        if (!CaustiqConfig.get().enabled()) return;
        if (!discoveryFinished) {
            if (!discoveryStarted) {
                if (SCAN_RESULT.get() != null) {
                    discoveryStarted = true;
                    beginDiscovery();
                }
            } else {
                compileNextProgram();
            }
        }
        executeFinalPass();
    }

    private static void compileNextProgram() {
        if (sources == null || nextProgram >= pending.size()) {
            discoveryFinished = true;
            closeSources();
            CaustiqClient.LOGGER.info("Caustiq shader compilation finished: {} linked program(s).", COMPILED.size());
            return;
        }
        int budget = CaustiqConfig.get().compileBudget();
        for (int compiledThisFrame = 0; compiledThisFrame < budget && nextProgram < pending.size(); compiledThisFrame++) {
            ShaderPackProgramCatalog.Program program = pending.get(nextProgram++);
            try {
                COMPILED.add(new ShaderProgramCompiler().compile(program, sources, selectedDefines));
                CaustiqClient.LOGGER.info("Compiled shader program '{}' ({} linked).", program.name(), COMPILED.size());
            } catch (IOException | RuntimeException exception) {
                CaustiqClient.LOGGER.error("Shader program '{}' failed: {}", program.name(), exception.getMessage());
            }
        }
    }

    private static void executeFinalPass() {
        if (postProcessFailureLogged) return;
        CompiledShaderProgram finalProgram = COMPILED.stream()
                .filter(program -> program.name().equalsIgnoreCase("final"))
                .findFirst().orElse(null);
        if (finalProgram == null) return;
        observeNvidiumTerrainBackend();
        try {
            RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
            POST_PROCESS.render(mainTarget, finalProgram);
        } catch (RuntimeException exception) {
            postProcessFailureLogged = true;
            CaustiqClient.LOGGER.error("Caustiq disabled its final pass after a render-target failure.", exception);
        }
    }

    /** Reports actual Nvidium terrain-renderer calls, not merely the presence of its mod jar. */
    private static void observeNvidiumTerrainBackend() {
        if (!FabricLoader.getInstance().isModLoaded("nvidium")) return;
        NvidiumRenderBridge.Snapshot snapshot = NvidiumRenderBridge.snapshot();
        if (snapshot.completedSolidPasses() > lastReportedNvidiumSolidFrames) {
            lastReportedNvidiumSolidFrames = snapshot.completedSolidPasses();
            warnedNvidiumBackendNotObserved = false;
            CaustiqClient.LOGGER.info(
                    "Nvidium renderFrame call completed before CAUSTIQ post-processing: {} completed solid pass(es), {} completed translucent pass(es).",
                    snapshot.completedSolidPasses(), snapshot.completedTranslucentPasses());
        } else if (snapshot.startedSolidPasses() == 0 && !warnedNvidiumBackendNotObserved) {
            warnedNvidiumBackendNotObserved = true;
            CaustiqClient.LOGGER.warn(
                    "Nvidium is installed, but CAUSTIQ has not observed a completed Nvidium renderFrame call. Its accelerated backend may be disabled or the active renderer path may differ; CAUSTIQ will not claim Nvidium acceleration is active.");
        }
    }

    private static void beginDiscovery() {
        ShaderPackScanResult result = SCAN_RESULT.get();
        if (result == null || result.packs().isEmpty()) {
            discoveryFinished = true;
            return;
        }
        String selectedId = CaustiqConfig.get().selectedPackId();
        ShaderPackDescriptor selected = result.packs().stream()
                .filter(pack -> pack.id().equals(selectedId))
                .findFirst().orElse(null);
        if (selected == null) {
            CaustiqClient.LOGGER.info("Shader compilation is idle: select one of the {} discovered packs in Video Settings.",
                    result.packs().size());
            discoveryFinished = true;
            return;
        }
        try {
            sources = ShaderPackSources.open(selected);
            List<String> shaderFiles = sources.listShaderFiles();
            if (shaderFiles.contains("block.properties")) {
                Map<String, Integer> blockIds = new IrisBlockPropertiesParser()
                        .parse(sources.read("block.properties"));
                NvidiumIrisVertexContext.setBlockIds(blockIds);
                CaustiqClient.LOGGER.info("Loaded {} block.properties material ID mapping(s) for mc_Entity.",
                        blockIds.size());
            } else {
                NvidiumIrisVertexContext.setBlockIds(Map.of());
                CaustiqClient.LOGGER.info("Shader pack '{}' has no block.properties; mc_Entity uses registry block IDs.",
                        selected.displayName());
            }
            IrisShaderPackModel packModel = new IrisShaderPackParser().parse(sources);
            activePassPlan = new IrisTerrainPassScheduler().schedule(packModel, null);
            CaustiqClient.LOGGER.info(
                    "Built Iris pass schedule for '{}': {} ordered program pass(es), {} declared render target(s): {}.",
                    selected.displayName(), activePassPlan.passes().size(), activePassPlan.targets().size(),
                    activePassPlan.orderedProgramNames());
            List<ShaderPackProgramCatalog.Program> discoveredPrograms = new ArrayList<>();
            List<String> invalidPrograms = new ArrayList<>();
            for (IrisShaderPackModel.Program program : packModel.base().programs().values()) {
                ShaderPackProgramCatalog.Group group = classifyProgram(program.name());
                ShaderPackProgramCatalog.Program catalogProgram = new ShaderPackProgramCatalog.Program(
                        program.name(), group, 0, program.stages());
                if (!catalogProgram.isExecutableShape()) invalidPrograms.add(program.name());
                discoveredPrograms.add(catalogProgram);
            }
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog.Result(
                    discoveredPrograms, List.of(), invalidPrograms);

            if (!packModel.dimensionOverrides().isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' defines {} independent Iris dimension program set(s) ({}). CAUSTIQ will not collapse these into the overworld program set.",
                        selected.displayName(), packModel.dimensionOverrides().size(),
                        String.join(", ", packModel.dimensionOverrides().keySet()));
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            if (!packModel.requiredFeatures().isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires Iris feature flag(s) {}. CAUSTIQ's Nvidium translation pipeline does not currently implement Iris feature negotiation; refusing partial activation.",
                        selected.displayName(), packModel.requiredFeatures());
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            if (!invalidPrograms.isEmpty()) {
                CaustiqClient.LOGGER.warn("Shader pack '{}' contains program(s) without a vertex+fragment pair or compute stage: {}",
                        selected.displayName(), invalidPrograms);
            }
            if (!packModel.diagnostics().isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' uses Iris features that CAUSTIQ has parsed but cannot execute yet: {}. Refusing partial activation.",
                        selected.displayName(), packModel.diagnostics());
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            IrisShaderPackModel.Program finalProgram = packModel.base().programs().get("final");
            if (packModel.base().programs().size() != 1 || finalProgram == null
                    || !finalProgram.hasVertexAndFragment() || finalProgram.stages().size() != 2) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' does not have the exact standalone final.vsh + final.fsh program shape supported by the current post-process path. Refusing partial activation.",
                        selected.displayName());
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            if (!catalog.isFinalOnly()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires the multi-pass world renderer. CAUSTIQ currently supports only a standalone final pass; skipping activation rather than rendering an incorrect partial pipeline. Detected {} base program(s), {} dimension override(s), {} parser diagnostic(s).",
                        selected.displayName(), catalog.programs().size(),
                        packModel.dimensionOverrides().size(), packModel.diagnostics().size());
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            pending = catalog.programs();

            Map<String, String> defines = new LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : packModel.options().options()) {
                defines.put(option.name(), CaustiqConfig.get().option(
                        selected.id(), option.name(), option.defaultValue()));
            }
            selectedDefines = Map.copyOf(defines);

            CaustiqClient.LOGGER.info(
                    "Parsed Iris pack '{}': {} base program(s), {} dimension override(s), {} required feature(s), {} preprocessed shader stage(s), {} option define(s).",
                    selected.displayName(), packModel.base().programs().size(),
                    packModel.dimensionOverrides().size(), packModel.requiredFeatures().size(),
                    packModel.preprocessedSources().size(), selectedDefines.size());
            if (pending.isEmpty()) {
                discoveryFinished = true;
                closeSources();
            }
        } catch (IOException exception) {
            CaustiqClient.LOGGER.error("Unable to prepare shader pack '{}': {}", selected.displayName(), exception.getMessage());
            discoveryFinished = true;
            closeSources();
        }
    }

    private static ShaderPackProgramCatalog.Group classifyProgram(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("final")) return ShaderPackProgramCatalog.Group.FINAL;
        if (lower.startsWith("composite")) return ShaderPackProgramCatalog.Group.COMPOSITE;
        if (lower.startsWith("deferred")) return ShaderPackProgramCatalog.Group.DEFERRED;
        if (lower.startsWith("gbuffers_")) return ShaderPackProgramCatalog.Group.GBUFFERS;
        if (lower.startsWith("shadowcomp")) return ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE;
        if (lower.startsWith("shadow")) return ShaderPackProgramCatalog.Group.SHADOW;
        if (lower.startsWith("prepare")) return ShaderPackProgramCatalog.Group.PREPARE;
        if (lower.startsWith("begin")) return ShaderPackProgramCatalog.Group.BEGIN;
        if (lower.startsWith("setup")) return ShaderPackProgramCatalog.Group.SETUP;
        return ShaderPackProgramCatalog.Group.UNKNOWN;
    }

    private static void closeSources() {
        if (sources != null) {
            try { sources.close(); }
            catch (IOException exception) {
                CaustiqClient.LOGGER.warn("Unable to close shader sources: {}", exception.getMessage());
            }
            sources = null;
        }
    }

    public static void shutdown() {
        requestReload();
        NvidiumGBufferTargets.destroy();
        SCAN_RESULT.set(null);
    }
}
