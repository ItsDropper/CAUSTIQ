package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.IrisNvidiumTerrainTranslator;
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
    private static IrisShaderPackModel preparedPackModel;
    private static String preparedPackId = "";
    private static IrisNvidiumTerrainTranslator preparedTerrainTranslator;
    private static IrisTerrainPassScheduler.Plan preparedTerrainPlan;
    private static String preparedTerrainDimensionKey = "";
    private static Map<String, String> preparedDimensionMappings = Map.of();
    private static boolean preparedTerrainAdapterRegistered;
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
        prepareSelectedNvidiumTerrainAdapter();
    }

    /** Invalidates compiled resources after a UI/configuration change. Render-thread only. */
    public static void requestReload() {
        closeSources();
        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        NvidiumGBufferTargets.resetOutputRouting();
        POST_PROCESS.close();
        NvidiumGBufferTargets.configureTargets(Map.of());
        COMPILED.forEach(CompiledShaderProgram::close);
        COMPILED.clear();
        pending = List.of();
        activePassPlan = null;
        selectedDefines = Map.of();
        nextProgram = 0;
        discoveryStarted = false;
        discoveryFinished = false;
        postProcessFailureLogged = false;
        preparedPackModel = null;
        preparedPackId = "";
        preparedTerrainTranslator = null;
        preparedTerrainPlan = null;
        preparedTerrainDimensionKey = "";
        preparedDimensionMappings = Map.of();
        preparedTerrainAdapterRegistered = false;
        prepareSelectedNvidiumTerrainAdapter();
    }

    public static IrisTerrainPassScheduler.Plan activePassPlan() { return activePassPlan; }

    /**
     * Parses the selected pack and installs its terrain translator before
     * Nvidium constructs its world renderer. Nvidium's Iris opt-out is checked
     * during renderer construction, so waiting for the first world-render
     * callback is too late.
     */
    private static void prepareSelectedNvidiumTerrainAdapter() {
        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        preparedPackModel = null;
        preparedPackId = "";
        preparedTerrainTranslator = null;
        preparedTerrainPlan = null;
        preparedTerrainDimensionKey = "";
        preparedDimensionMappings = Map.of();
        preparedTerrainAdapterRegistered = false;
        if (!CaustiqConfig.get().enabled()
                || !FabricLoader.getInstance().isModLoaded("nvidium")
                || !NvidiumShaderProgramBridge.hasNativeSourceAdapter()) {
            return;
        }

        ShaderPackScanResult result = SCAN_RESULT.get();
        if (result == null) return;
        String selectedId = CaustiqConfig.get().selectedPackId();
        ShaderPackDescriptor selected = result.packs().stream()
                .filter(pack -> pack.id().equals(selectedId))
                .findFirst().orElse(null);
        if (selected == null) return;

        try (ShaderPackSources selectedSources = ShaderPackSources.open(selected)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(selectedSources);
            if (!model.requiredFeatures().isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires Iris feature flags {}; Nvidium terrain translation remains disabled.",
                        selected.displayName(), model.requiredFeatures());
                return;
            }

            String dimensionKey = selectSupportedTerrainDimension(model);
            if (dimensionKey == null) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' has terrain programs in multiple dimension sets that CAUSTIQ cannot select safely yet.",
                        selected.displayName());
                return;
            }

            Map<String, String> optionDefines = new LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : model.options().options()) {
                optionDefines.put(option.name(), CaustiqConfig.get().option(
                        selected.id(), option.name(), option.defaultValue()));
            }
            IrisNvidiumTerrainTranslator translator = IrisNvidiumTerrainTranslator.from(
                    model, dimensionKey.isEmpty() ? null : dimensionKey,
                    "gbuffers_terrain", optionDefines);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler()
                    .schedule(model, dimensionKey.isEmpty() ? null : dimensionKey);

            preparedPackModel = model;
            preparedPackId = selected.id();
            preparedTerrainTranslator = translator;
            preparedTerrainPlan = plan;
            preparedTerrainDimensionKey = dimensionKey;
            preparedDimensionMappings = model.dimensionMappings();

            CaustiqClient.LOGGER.info(
                    "Prepared selected pack '{}' for Nvidium terrain translation in dimension set '{}'; MRT routing: {}.",
                    selected.displayName(), dimensionKey.isEmpty() ? "base" : dimensionKey,
                    translator.renderTargetIndices());
            activateTerrainAdapterForCurrentWorld();
        } catch (IOException | RuntimeException exception) {
            NvidiumShaderProgramBridge.unregisterTerrainAdapter();
            preparedPackModel = null;
            preparedPackId = "";
            preparedTerrainTranslator = null;
            preparedTerrainPlan = null;
            preparedTerrainDimensionKey = "";
            preparedDimensionMappings = Map.of();
            preparedTerrainAdapterRegistered = false;
            CaustiqClient.LOGGER.warn(
                    "Selected shader pack '{}' cannot be installed into Nvidium's terrain compiler; preserving Nvidium's Iris safety gate: {}",
                    selected.displayName(), exception.getMessage());
        }
    }

    /**
     * Returns the one terrain program-set key that can be selected without
     * guessing a dimension. Root programs apply everywhere; a single
     * dimension-only terrain set is activated only in that dimension.
     */
    private static String selectSupportedTerrainDimension(IrisShaderPackModel model) {
        boolean hasBaseTerrain = model.base().programs().containsKey("gbuffers_terrain");
        List<String> overrides = model.dimensionOverrides().entrySet().stream()
                .filter(entry -> entry.getValue().programs().containsKey("gbuffers_terrain"))
                .map(Map.Entry::getKey)
                .toList();
        if (hasBaseTerrain) return overrides.isEmpty() ? "" : null;
        return overrides.size() == 1 ? overrides.getFirst() : null;
    }

    /**
     * Called from Nvidium's Iris compatibility check while RenderSectionManager
     * is being constructed. This is the last safe point to select a
     * dimension-specific terrain program before Nvidium compiles its shaders.
     */
    public static boolean activateTerrainAdapterForCurrentWorld() {
        IrisNvidiumTerrainTranslator translator = preparedTerrainTranslator;
        if (translator == null) return false;
        if (!preparedTerrainDimensionKey.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return false;
            String dimensionId = minecraft.level.dimension().location().toString();
            String directory = preparedDimensionMappings.getOrDefault(
                    dimensionId, builtInDimensionDirectory(dimensionId));
            if (!preparedTerrainDimensionKey.equals(directory)) {
                if (preparedTerrainAdapterRegistered) {
                    NvidiumShaderProgramBridge.unregisterTerrainAdapter();
                    NvidiumGBufferTargets.resetOutputRouting();
                    NvidiumGBufferTargets.configureTargets(Map.of());
                    preparedTerrainAdapterRegistered = false;
                    NvidiumRenderBridge.reloadShaders();
                }
                return false;
            }
        }

        if (!preparedTerrainAdapterRegistered) {
            NvidiumGBufferTargets.configureTargets(
                    preparedTerrainPlan == null ? Map.of() : preparedTerrainPlan.targets());
            NvidiumShaderProgramBridge.registerTerrainAdapter(
                    translator::adapt, translator.renderTargetIndices());
            preparedTerrainAdapterRegistered = true;
            if (!NvidiumRenderBridge.reloadShaders()) {
                CaustiqClient.LOGGER.info(
                        "Nvidium terrain adapter is ready for its next renderer shader build; no live renderer was available to reload.");
            }
        }
        return true;
    }

    private static String builtInDimensionDirectory(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> "world0";
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> dimensionId;
        };
    }

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

    private static void rebuildTerrainSections() {
        Object levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer == null) return;
        try {
            levelRenderer.getClass().getMethod("allChanged").invoke(levelRenderer);
            CaustiqClient.LOGGER.info("Requested terrain section rebuild after changing the shader pack block material map.");
        } catch (ReflectiveOperationException exception) {
            CaustiqClient.LOGGER.warn("Could not request a terrain section rebuild after changing block.properties IDs.", exception);
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
                if (NvidiumIrisVertexContext.setBlockIds(blockIds)) rebuildTerrainSections();
                CaustiqClient.LOGGER.info("Loaded {} block.properties material ID mapping(s) for mc_Entity.",
                        blockIds.size());
            } else {
                if (NvidiumIrisVertexContext.setBlockIds(Map.of())) rebuildTerrainSections();
                CaustiqClient.LOGGER.info("Shader pack '{}' has no block.properties; mc_Entity uses registry block IDs.",
                        selected.displayName());
            }
            IrisShaderPackModel packModel = selected.id().equals(preparedPackId) && preparedPackModel != null
                    ? preparedPackModel : new IrisShaderPackParser().parse(sources);
            activePassPlan = new IrisTerrainPassScheduler().schedule(
                    packModel, preparedTerrainDimensionKey.isEmpty() ? null : preparedTerrainDimensionKey);
            NvidiumGBufferTargets.configureTargets(activePassPlan.targets());
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
