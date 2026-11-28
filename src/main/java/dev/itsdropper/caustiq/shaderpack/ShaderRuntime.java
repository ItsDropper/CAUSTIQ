package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumGBufferTargets;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisVertexContext;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisTextureBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumIrisUniformBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumRenderBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShadowRenderer;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.IrisNvidiumTerrainTranslator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderRuntime {
    private static final Set<String> SUPPORTED_REQUIRED_IRIS_FEATURES = Set.of("CUSTOM_IMAGES");
    private static final AtomicReference<ShaderPackScanResult> SCAN_RESULT = new AtomicReference<>();
    private static final List<CompiledShaderProgram> COMPILED = new ArrayList<>();
    private static final ShaderPostProcessPass POST_PROCESS = new ShaderPostProcessPass();
    private static final IrisGlPassExecutor IRIS_PASS_EXECUTOR = new IrisGlPassExecutor();
    private static final ShaderRuntimeStatusTracker STATUS = new ShaderRuntimeStatusTracker();
    private static IrisShaderResources irisShaderResources = IrisShaderResources.empty();
    private static final Map<String, IrisGlProgram> IRIS_GL_PROGRAMS = new LinkedHashMap<>();
    private static boolean discoveryStarted, discoveryFinished;
    private static final ShaderPackModelPreloadCache<ParsedShaderPack> PRELOADED_PACK =
            new ShaderPackModelPreloadCache<>();
    private static final AtomicLong PRELOAD_STATUS_GENERATION = new AtomicLong();
    private static ShaderPackSources sources;
    private static IrisShaderPackModel preparedPackModel;
    private static String preparedPackId = "";
    private static Map<String, IrisNvidiumTerrainTranslator> preparedTerrainTranslators = Map.of();
    private static Map<String, IrisNvidiumTerrainTranslator> preparedTranslucentTerrainTranslators = Map.of();
    private static Set<String> preparedUnsupportedTerrainDimensions = Set.of();
    private static IrisTerrainPassScheduler.Plan preparedTerrainPlan;
    private static Map<String, String> preparedDimensionMappings = Map.of();
    private static String activeTerrainDimensionKey = "";
    private static boolean preparedTerrainAdapterRegistered;
    private static List<ShaderPackProgramCatalog.Program> pending = List.of();
    private static IrisTerrainPassScheduler.Plan activePassPlan;
    private static Map<String, String> selectedDefines = Map.of();
    private static int nextProgram;
    private static boolean postProcessFailureLogged;
    private static boolean irisPassFailureLogged;
    private static long lastReportedNvidiumSolidFrames;
    private static boolean warnedNvidiumBackendNotObserved;
    private static int lastVoxelImageClearFrame = Integer.MIN_VALUE;
    private static int strictGpuCiFrames;
    private static int strictGpuCiBaselineFrame;
    private static NvidiumRenderBridge.Snapshot strictGpuCiBaseline;
    private static boolean strictGpuCiCameraRotated;
    private static boolean strictGpuCiFinished;

    private ShaderRuntime() { }

    public static void publishScanResult(ShaderPackScanResult result) {
        publishScanResult(result, true);
    }

    /** Publishes startup discovery without creating GL resources before Nvidium's pre-world check. */
    public static void publishStartupScanResult(ShaderPackScanResult result) {
        publishScanResult(result, false);
    }

    private static void publishScanResult(ShaderPackScanResult result, boolean prepareTerrainAdapter) {
        PRELOAD_STATUS_GENERATION.incrementAndGet();
        discoveryStarted = false;
        discoveryFinished = false;
        SCAN_RESULT.set(result);
        resetStatusForSelection(result);
        CompletableFuture<ParsedShaderPack> preload = preloadSelectedPackModel(result);
        trackPreloadedPackStatus(result, preload);
        if (prepareTerrainAdapter) prepareSelectedNvidiumTerrainAdapter();
    }

    /** Starts the selected pack's CPU-only parse as soon as startup discovery completes. */
    public static void preloadSelectedPackModelForStartup(ShaderPackScanResult result) {
        preloadSelectedPackModel(result);
    }

    private static void trackPreloadedPackStatus(
            ShaderPackScanResult result,
            CompletableFuture<ParsedShaderPack> preload
    ) {
        if (preload == null) return;
        String selectedId = CaustiqConfig.get().selectedPackId();
        ShaderPackDescriptor selected = result.packs().stream()
                .filter(pack -> pack.id().equals(selectedId))
                .findFirst().orElse(null);
        if (selected == null) return;

        long generation = PRELOAD_STATUS_GENERATION.get();
        STATUS.preparing(selected.displayName());
        preload.whenComplete((parsed, failure) -> {
            if (PRELOAD_STATUS_GENERATION.get() != generation
                    || !PRELOADED_PACK.isCurrent(selected.id(), result, preload)) {
                return;
            }
            if (failure != null) {
                STATUS.failed("Unable to read the selected shader pack (" + safeFailureSummary(failure)
                        + "). Check file access and pack integrity, then use Reload to retry.");
            } else {
                resetStatusForSelection(result);
            }
        });
    }

    /** Returns an immutable, privacy-safe snapshot for the runtime status screen. */
    public static ShaderRuntimeStatus status() {
        return STATUS.snapshot();
    }

    private static void resetStatusForSelection(ShaderPackScanResult result) {
        String selectedPackName = "";
        if (result != null) {
            String selectedId = CaustiqConfig.get().selectedPackId();
            selectedPackName = result.packs().stream()
                    .filter(pack -> pack.id().equals(selectedId))
                    .map(ShaderPackDescriptor::displayName)
                    .findFirst().orElse("");
        }
        STATUS.resetForSelection(
                CaustiqConfig.get().enabled(),
                FabricLoader.getInstance().isModLoaded("nvidium"),
                selectedPackName);
    }

    /** Invalidates compiled resources after a UI/configuration change. Render-thread only. */
    public static void requestReload() {
        PRELOAD_STATUS_GENERATION.incrementAndGet();
        closeSources();
        NvidiumShadowRenderer.configure(false, 2048, 128.0f);
        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        NvidiumGBufferTargets.resetOutputRouting();
        POST_PROCESS.close();
        closeIrisPrograms();
        irisShaderResources.close();
        irisShaderResources = IrisShaderResources.empty();
        IRIS_PASS_EXECUTOR.close();
        NvidiumGBufferTargets.destroy();
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
        irisPassFailureLogged = false;
        preparedPackModel = null;
        preparedPackId = "";
        preparedTerrainTranslators = Map.of();
        preparedTranslucentTerrainTranslators = Map.of();
        preparedUnsupportedTerrainDimensions = Set.of();
        preparedTerrainPlan = null;
        preparedDimensionMappings = Map.of();
        activeTerrainDimensionKey = "";
        preparedTerrainAdapterRegistered = false;
        ShaderPackScanResult currentScan = SCAN_RESULT.get();
        resetStatusForSelection(currentScan);
        CompletableFuture<ParsedShaderPack> preload = preloadSelectedPackModel(currentScan);
        trackPreloadedPackStatus(currentScan, preload);
        prepareSelectedNvidiumTerrainAdapter();
    }

    /** Clears Photon voxel data once before the first shadow terrain draw of each frame. */
    public static void prepareShadowVoxelImage(int frameCounter) {
        if (lastVoxelImageClearFrame == frameCounter) return;
        irisShaderResources.clearImage("voxel_img");
        lastVoxelImageClearFrame = frameCounter;
    }

    /** Uploads the selected pack's custom uniforms to Nvidium's active terrain program. */
    public static void uploadTerrainCustomUniforms(int program) {
        if (program == 0) return;
        try {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            irisShaderResources.uploadUniforms(program,
                    NvidiumIrisUniformBridge.expressionVariables(target.width, target.height, selectedDefines),
                    IrisNvidiumTerrainTranslator.uniformAliases());
        } catch (RuntimeException exception) {
            CaustiqClient.LOGGER.warn("Could not evaluate or upload custom Iris terrain uniforms: {}", exception.getMessage());
        }
    }

    /** Binds declared custom textures to the active Nvidium terrain program. */
    public static void bindTerrainCustomTextures(int program, int firstTextureUnit) {
        try {
            NvidiumIrisTextureBridge.bindCustomTextures(irisShaderResources, program, firstTextureUnit);
        } catch (RuntimeException exception) {
            CaustiqClient.LOGGER.warn("Could not bind custom Iris terrain textures: {}", exception.getMessage());
        }
    }

    /** Returns the last frame's Iris-owned color target for a terrain sampler. */
    /** Previous completed frame's Iris-owned target, sampled by Nvidium terrain. */
    public static int irisColorTexture(int index) {
        return IRIS_PASS_EXECUTOR.terrainSamplerTexture(index);
    }

    /** Current frame's writable terrain target, attached before Nvidium rasterization. */
    public static int irisTerrainOutputTexture(int index) {
        return IRIS_PASS_EXECUTOR.terrainOutputTexture(index);
    }

    /** Ensures high-index terrain MRT targets exist before Nvidium attaches them. */
    public static void prepareTerrainOutputs(int width, int height) {
        if (activePassPlan != null) IRIS_PASS_EXECUTOR.prepareTerrainOutputs(activePassPlan, width, height);
    }

    /** Publishes terrain MRT writes when no fullscreen pass runs this frame. */
    public static void publishTerrainOutputs() {
        if (activePassPlan != null) IRIS_PASS_EXECUTOR.publishTerrainOutputs(activePassPlan);
    }

    public static IrisTerrainPassScheduler.Plan activePassPlan() { return activePassPlan; }

    /** Selected shader-pack define exposed to render-time built-in uniform providers. */
    public static String selectedDefine(String name, String fallback) {
        return selectedDefines.getOrDefault(name, fallback);
    }

    /**
     * Parses the selected pack and installs its terrain translator before
     * Nvidium constructs its world renderer. Nvidium's Iris opt-out is checked
     * during renderer construction, so waiting for the first world-render
     * callback is too late.
     */
    public static void prepareSelectedNvidiumTerrainAdapter() {
        // Startup discovery is asynchronous. CaustiqClient waits for its result at
        // Nvidium's existing Iris safety check during RenderSectionManager construction.
        // This method allocates Iris images and resolves pack textures, so it must only
        // run once the client resources and GL context are ready.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager() == null) {
            CaustiqClient.LOGGER.debug(
                    "Deferring Nvidium shader-pack preparation until Minecraft resources are initialized.");
            return;
        }

        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        NvidiumGBufferTargets.destroy();
        preparedPackModel = null;
        preparedPackId = "";
        preparedTerrainTranslators = Map.of();
        preparedTranslucentTerrainTranslators = Map.of();
        preparedUnsupportedTerrainDimensions = Set.of();
        preparedTerrainPlan = null;
        preparedDimensionMappings = Map.of();
        activeTerrainDimensionKey = "";
        preparedTerrainAdapterRegistered = false;
        NvidiumShadowRenderer.configure(false, 2048, 128.0f);
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

        PRELOAD_STATUS_GENERATION.incrementAndGet();
        STATUS.preparing(selected.displayName());
        try {
            long parseWaitStarted = System.nanoTime();
            CompletableFuture<ParsedShaderPack> parseFuture = preloadSelectedPackModel(result);
            if (parseFuture == null) {
                throw new IllegalStateException("Selected shader pack changed while Nvidium preparation was starting.");
            }
            ParsedShaderPack parsedPack = parseFuture.join();
            long parseWaitMillis = (System.nanoTime() - parseWaitStarted) / 1_000_000L;
            IrisShaderPackModel model = parsedPack.model();
            long resourceLoadStarted = System.nanoTime();
            try (ShaderPackSources selectedSources = ShaderPackSources.open(selected)) {
            warnUnsupportedShadowGeometry(model, selected.displayName());
            Map<String, String> optionDefines = new LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : model.options().options()) {
                optionDefines.put(option.name(), CaustiqConfig.get().option(
                        selected.id(), option.name(), option.defaultValue()));
            }
            if (Boolean.getBoolean("caustiq.strictGpuCi")) {
                // Photon gates its setup/shadow-composite compute programs behind
                // COLORED_LIGHTS. Force that path only in the dedicated GPU test.
                optionDefines.put("COLORED_LIGHTS", "1");
                CaustiqClient.LOGGER.info(
                        "CAUSTIQ_STRICT_GPU_CI: enabling Photon COLORED_LIGHTS to exercise its compute pipeline.");
            }
            selectedDefines = Map.copyOf(optionDefines);
            IrisShaderResources loadedResources = IrisShaderResources.load(
                    model.properties(), selectedSources, optionDefines);
            irisShaderResources.close();
            irisShaderResources = loadedResources;
            long resourceLoadMillis = (System.nanoTime() - resourceLoadStarted) / 1_000_000L;
            CaustiqClient.LOGGER.info(
                    "Nvidium shader-pack preparation timings for '{}': background parse={}ms, render-thread parse wait={}ms, GL resource load/upload={}ms.",
                    selected.displayName(), parsedPack.parseMillis(), parseWaitMillis, resourceLoadMillis);
            Set<String> unsupportedRequiredFeatures = unsupportedRequiredFeatures(model);
            if (!unsupportedRequiredFeatures.isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires Iris feature flag(s) CAUSTIQ cannot provide: {}; Nvidium terrain translation remains disabled.",
                        selected.displayName(), unsupportedRequiredFeatures);
                STATUS.failed("This pack requires unsupported Iris features: " + unsupportedRequiredFeatures
                        + ". Choose a compatible pack or remove those requirements.");
                return;
            }

            Map<String, IrisNvidiumTerrainTranslator> translators = new LinkedHashMap<>();
            Map<String, IrisNvidiumTerrainTranslator> translucentTranslators = new LinkedHashMap<>();
            Set<String> unsupportedDimensions = new LinkedHashSet<>();
            if (model.base().programs().containsKey("gbuffers_terrain")) {
                try {
                    translators.put("", IrisNvidiumTerrainTranslator.from(
                            model, null, "gbuffers_terrain", optionDefines));
                } catch (RuntimeException exception) {
                    unsupportedDimensions.add("");
                    CaustiqClient.LOGGER.warn(
                            "Shader pack '{}' base gbuffers_terrain cannot be translated: {}",
                            selected.displayName(), exception.getMessage());
                }
            }

            model.dimensionOverrides().forEach((directory, programSet) -> {
                if (!programSet.programs().containsKey("gbuffers_terrain")) return;
                try {
                    translators.put(directory, IrisNvidiumTerrainTranslator.from(
                            model, directory, "gbuffers_terrain", optionDefines));
                } catch (RuntimeException exception) {
                    unsupportedDimensions.add(directory);
                    CaustiqClient.LOGGER.warn(
                            "Shader pack '{}' {} gbuffers_terrain cannot be translated: {}",
                            selected.displayName(), directory, exception.getMessage());
                }
            });

            prepareTranslucentTerrainTranslators(
                    model, optionDefines, translucentTranslators, selected.displayName());

            if (translators.isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' has no gbuffers_terrain program that the current Nvidium ABI can translate.",
                        selected.displayName());
                NvidiumShadowRenderer.configure(false, 2048, 128.0f);
                NvidiumShaderProgramBridge.unregisterTerrainAdapter();
                STATUS.failed("This pack has no terrain program that can be prepared for the current Nvidium ABI.");
                return;
            }

            preparedPackModel = model;
            preparedPackId = selected.id();
            preparedTerrainTranslators = Map.copyOf(translators);
            preparedTranslucentTerrainTranslators = Map.copyOf(translucentTranslators);
            preparedUnsupportedTerrainDimensions = Set.copyOf(unsupportedDimensions);
            preparedTerrainPlan = new IrisTerrainPassScheduler().schedule(model, "minecraft:overworld");
            preparedDimensionMappings = model.dimensionMappings();

            CaustiqClient.LOGGER.info(
                    "Prepared shader pack '{}' for Nvidium terrain translation: {} dimension program(s), {} unsupported dimension program(s).",
                    selected.displayName(), translators.size(), unsupportedDimensions.size());
            CaustiqClient.LOGGER.info(
                    "Prepared {} separate Iris translucent terrain program(s) for Nvidium.",
                    translucentTranslators.size());
            if (activateTerrainAdapterForCurrentWorld()) {
                STATUS.prepared();
            } else {
                STATUS.failed("The selected pack has no supported terrain program for the active dimension.");
            }
            }
        } catch (IOException | RuntimeException exception) {
            irisShaderResources.close();
            irisShaderResources = IrisShaderResources.empty();
            NvidiumShaderProgramBridge.unregisterTerrainAdapter();
            NvidiumShadowRenderer.configure(false, 2048, 128.0f);
            preparedPackModel = null;
            preparedPackId = "";
            preparedTerrainTranslators = Map.of();
            preparedTranslucentTerrainTranslators = Map.of();
            preparedUnsupportedTerrainDimensions = Set.of();
            preparedTerrainPlan = null;
            preparedDimensionMappings = Map.of();
            activeTerrainDimensionKey = "";
            preparedTerrainAdapterRegistered = false;
            CaustiqClient.LOGGER.warn(
                    "Selected shader pack '{}' cannot be installed into Nvidium's terrain compiler; preserving Nvidium's Iris safety gate: {}",
                    selected.displayName(), exception.getMessage());
            STATUS.failed("Unable to prepare the selected shader pack (" + safeFailureSummary(exception)
                    + "). See latest.log for details, then use Reload to retry.");
        }
    }


    /**
     * Parses the selected pack's CPU-only Iris model while the client is at the
     * title screen. Nvidium asks for its compatibility decision during world
     * renderer construction, so doing ZIP traversal, GLSL preprocessing and
     * program analysis there needlessly stalls the Loading Terrain screen.
     * OpenGL-backed image/texture creation remains on the render thread.
     */
    private static synchronized CompletableFuture<ParsedShaderPack> preloadSelectedPackModel(
            ShaderPackScanResult result) {
        if (result == null
                || !CaustiqConfig.get().enabled()
                || !FabricLoader.getInstance().isModLoaded("nvidium")
                || !NvidiumShaderProgramBridge.hasNativeSourceAdapter()) {
            PRELOADED_PACK.invalidate();
            return null;
        }
        String selectedId = CaustiqConfig.get().selectedPackId();
        ShaderPackDescriptor selected = result.packs().stream()
                .filter(pack -> pack.id().equals(selectedId))
                .findFirst().orElse(null);
        if (selected == null) {
            PRELOADED_PACK.invalidate();
            return null;
        }

        return PRELOADED_PACK.getOrStart(selected.id(), result, () -> CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            try (ShaderPackSources packSources = ShaderPackSources.open(selected)) {
                IrisShaderPackModel model = new IrisShaderPackParser().parse(packSources);
                return new ParsedShaderPack(model, (System.nanoTime() - started) / 1_000_000L);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }));
    }

    private static String safeFailureSummary(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        // Exception messages can contain shader source excerpts or archive entry paths.
        // Keep the UI/report safe and leave detailed diagnostics in latest.log.
        return cause.getClass().getSimpleName();
    }

    private record ParsedShaderPack(IrisShaderPackModel model, long parseMillis) { }

    private static void warnUnsupportedShadowGeometry(IrisShaderPackModel model, String packName) {
        Set<String> programs = new LinkedHashSet<>(model.base().programs().keySet());
        model.dimensionOverrides().values().forEach(programSet ->
                programs.addAll(programSet.programs().keySet()));
        List<String> unsupported = programs.stream()
                .filter(name -> name.equals("shadow_entities") || name.equals("shadow_block"))
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            CaustiqClient.LOGGER.warn(
                    "Shader pack '{}' declares {}. CAUSTIQ's Nvidium shadow renderer currently renders terrain only; entity and block-entity geometry will not cast shader shadows.",
                    packName, unsupported);
        }
    }

    /**
     * Selects the terrain translator for the current Minecraft dimension before
     * Nvidium constructs its renderer. Dimension-specific packs can therefore
     * use separate overworld, nether, and end terrain programs safely.
     */
    public static boolean activateTerrainAdapterForCurrentWorld() {
        if (preparedTerrainTranslators.isEmpty()) return false;

        Minecraft minecraft = Minecraft.getInstance();
        String dimensionId = resolveDimensionId(minecraft.level == null
                ? null : minecraft.level.dimension().identifier().toString());
        String directory = dimensionId.isEmpty() ? ""
                : preparedDimensionMappings.getOrDefault(
                        dimensionId, builtInDimensionDirectory(dimensionId));

        if (preparedUnsupportedTerrainDimensions.contains(directory)) {
            deactivateTerrainAdapter("active dimension '" + dimensionId
                    + "' is marked unsupported for Nvidium terrain translation");
            return false;
        }

        IrisNvidiumTerrainTranslator translator = preparedTerrainTranslators.get(directory);
        String adapterKey = directory;
        if (translator == null) {
            if (preparedUnsupportedTerrainDimensions.contains("")) {
                deactivateTerrainAdapter("active dimension '" + dimensionId
                        + "' has no supported terrain translator and the base terrain translator is unsupported");
                return false;
            }
            translator = preparedTerrainTranslators.get("");
            adapterKey = "";
        }
        if (translator == null) {
            deactivateTerrainAdapter("no terrain translator for active dimension '" + dimensionId
                    + "' (mapped directory '" + directory + "'); available translator directories: "
                    + preparedTerrainTranslators.keySet());
            return false;
        }
        IrisNvidiumTerrainTranslator translucentTranslator =
                preparedTranslucentTerrainTranslators.get(directory);
        if (translucentTranslator == null) translucentTranslator =
                preparedTranslucentTerrainTranslators.get(adapterKey);
        if (translucentTranslator == null) translucentTranslator =
                preparedTranslucentTerrainTranslators.get("");

        if (preparedTerrainAdapterRegistered && !adapterKey.equals(activeTerrainDimensionKey)) {
            NvidiumShaderProgramBridge.unregisterTerrainAdapter();
            NvidiumGBufferTargets.resetOutputRouting();
            NvidiumGBufferTargets.configureTargets(Map.of());
            preparedTerrainAdapterRegistered = false;
        }
        if (!preparedTerrainAdapterRegistered) {
            IrisTerrainPassScheduler.Plan dimensionPlan = preparedPackModel == null
                    ? null : new IrisTerrainPassScheduler().schedule(
                            preparedPackModel, dimensionId.isEmpty() ? null : dimensionId);
            if (dimensionPlan != null && dimensionPlan.hasScaledTerrainWrites()) {
                String reason = "active dimension '" + dimensionId
                        + "' writes scaled terrain colortex targets unsupported by Nvidium MRT";
                NvidiumShadowRenderer.configure(false, 2048, 128.0f, reason);
                CaustiqClient.LOGGER.warn(
                        "Shader pack terrain program writes a scaled colortex target. Nvidium terrain MRT remains full-resolution; refusing to install this terrain adapter.");
                return false;
            }
            NvidiumGBufferTargets.configureTargets(
                    dimensionPlan == null ? Map.of() : dimensionPlan.targets());
            // Re-select the dimension-specific shadow_solid/shadow_cutout/shadow_water
            // programs before Nvidium recompiles its renderer for this dimension.
            if (preparedPackModel != null) {
                configureShadowRenderer(preparedPackModel, dimensionId.isEmpty() ? null : dimensionId);
            }
            NvidiumShaderProgramBridge.registerTerrainAdapter(
                    translator::adapt, translator.renderTargetIndices());
            if (translucentTranslator != null) {
                NvidiumShaderProgramBridge.registerTranslucentTerrainAdapter(
                        translucentTranslator::adapt, translucentTranslator.renderTargetIndices());
            }
            activeTerrainDimensionKey = adapterKey;
            preparedTerrainAdapterRegistered = true;
            if (!NvidiumRenderBridge.reloadShaders()) {
                CaustiqClient.LOGGER.info(
                        "Nvidium terrain adapter for '{}' is ready for its next renderer shader build; no live renderer was available to reload.",
                        adapterKey.isEmpty() ? "base" : adapterKey);
            }
        }
        return true;
    }

    /** Nvidium may construct its renderer before ClientLevel is attached; use Overworld as the initial pack context. */
    static String resolveDimensionId(String dimensionId) {
        return dimensionId == null || dimensionId.isBlank() ? "minecraft:overworld" : dimensionId;
    }

    private static void deactivateTerrainAdapter(String reason) {
        NvidiumShadowRenderer.configure(false, 2048, 128.0f, reason);
        if (!preparedTerrainAdapterRegistered) {
            return;
        }
        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        NvidiumGBufferTargets.resetOutputRouting();
        NvidiumGBufferTargets.destroy();
        preparedTerrainAdapterRegistered = false;
        activeTerrainDimensionKey = "";
        NvidiumRenderBridge.reloadShaders();
    }

    private static void configureShadowRenderer(IrisShaderPackModel model, String dimensionId) {
        Map<String, String> optionDefines = new LinkedHashMap<>();
        for (ShaderPackMetadata.Option option : model.options().options()) {
            optionDefines.put(option.name(), CaustiqConfig.get().option(
                    CaustiqConfig.get().selectedPackId(), option.name(), option.defaultValue()));
        }

        String solidProgram = selectShadowProgram(
                model, dimensionId, "shadow_solid", "shadow", "shadow_cutout");
        String cutoutProgram = selectShadowProgram(
                model, dimensionId, "shadow_cutout", "shadow_solid", "shadow");
        String translucentProgram = selectShadowProgram(
                model, dimensionId, "shadow_water", "shadow", "shadow_solid", "shadow_cutout");
        if (solidProgram == null) {
            String reason = "no solid shadow program in active dimension '" + dimensionId
                    + "' (mapped directory: " + model.dimensionMappings().getOrDefault(
                            dimensionId == null ? "" : dimensionId,
                            dimensionId == null ? "" : dimensionId)
                    + "; overrides: " + model.dimensionOverrides().keySet() + ")";
            NvidiumShadowRenderer.configure(false, 2048, 128.0f, reason);
            CaustiqClient.LOGGER.warn(
                    "Shader pack has no translatable Iris shadow terrain program for dimension '{}'; "
                            + "Nvidium shadow rendering remains disabled. Dimension mappings: {}, overrides: {}",
                    dimensionId, model.dimensionMappings(), model.dimensionOverrides().keySet());
            return;
        }

        final IrisNvidiumTerrainTranslator solid;
        try {
            solid = IrisNvidiumTerrainTranslator.from(model, dimensionId, solidProgram, optionDefines);
        } catch (RuntimeException exception) {
            String reason = "solid shadow program '" + solidProgram + "' failed translation in dimension '"
                    + dimensionId + "': " + exception.getMessage();
            NvidiumShadowRenderer.configure(false, 2048, 128.0f, reason);
            CaustiqClient.LOGGER.warn(
                    "CAUSTIQ cannot translate the required Iris solid shadow program '{}' for dimension '{}'; "
                            + "Nvidium shadow rendering is disabled. Available overrides: {}",
                    solidProgram, dimensionId, model.dimensionOverrides().keySet(), exception);
            return;
        }

        // Cutout and translucent shadow programs are optional specializations.
        // A failure in either must not disable the valid solid shadow pipeline:
        // reuse the translated solid program for that pass instead.
        IrisNvidiumTerrainTranslator cutout = translateOptionalShadowProgram(
                model, dimensionId, cutoutProgram, solid, optionDefines, "cutout");
        IrisNvidiumTerrainTranslator translucent = translateOptionalShadowProgram(
                model, dimensionId, translucentProgram, solid, optionDefines, "translucent");
        NvidiumShaderProgramBridge.registerShadowTerrainAdapter(solid::adapt);
        NvidiumShaderProgramBridge.registerShadowCutoutTerrainAdapter(cutout::adapt);
        NvidiumShaderProgramBridge.registerShadowTranslucentTerrainAdapter(translucent::adapt);

        int resolution = parseIntegerProperty(model.properties().get("shadowMapResolution"), 2048);
        float distance = parseFloatProperty(model.properties().get("shadowDistance"), 128.0f);
        float distanceMultiplier = parseFloatProperty(
                model.properties().get("shadowDistanceRenderMul"), 1.0f);
        NvidiumShadowRenderer.configure(true, resolution, distance * distanceMultiplier);
        CaustiqClient.LOGGER.info(
                "Prepared Nvidium shadow rasterizers with Iris programs solid='{}', cutout='{}', translucent='{}' ({}px, {:.1f}-block light-space radius).",
                solidProgram, cutoutProgram == null ? solidProgram : cutoutProgram,
                translucentProgram == null ? solidProgram : translucentProgram,
                Integer.highestOneBit(Math.max(256, Math.min(8192, resolution))),
                distance * distanceMultiplier);
    }

    private static IrisNvidiumTerrainTranslator translateOptionalShadowProgram(
            IrisShaderPackModel model, String dimensionId, String programName,
            IrisNvidiumTerrainTranslator fallback, Map<String, String> optionDefines, String passKind) {
        if (programName == null) return fallback;
        try {
            return IrisNvidiumTerrainTranslator.from(model, dimensionId, programName, optionDefines);
        } catch (RuntimeException exception) {
            CaustiqClient.LOGGER.warn(
                    "Iris {} shadow program '{}' could not be translated for dimension '{}'; "
                            + "using the valid solid shadow program for this pass: {}",
                    passKind, programName, dimensionId, exception.getMessage());
            return fallback;
        }
    }

    private static String selectShadowProgram(
            IrisShaderPackModel model, String dimensionId, String... candidates) {
        return IrisShadowProgramSelector.select(model, dimensionId, candidates);
    }

    private static void prepareTranslucentTerrainTranslators(
            IrisShaderPackModel model, Map<String, String> optionDefines,
            Map<String, IrisNvidiumTerrainTranslator> translators, String packName) {
        List<String> directories = new ArrayList<>();
        directories.add("");
        directories.addAll(model.dimensionOverrides().keySet());
        for (String directory : directories) {
            IrisShaderPackModel.ProgramSet programSet = directory.isEmpty()
                    ? model.base() : model.dimensionOverrides().get(directory);
            if (programSet == null) continue;
            String programName = programSet.programs().containsKey("gbuffers_water")
                    ? "gbuffers_water"
                    : programSet.programs().containsKey("gbuffers_terrain")
                    ? "gbuffers_terrain"
                    : model.base().programs().containsKey("gbuffers_water")
                    ? "gbuffers_water"
                    : model.base().programs().containsKey("gbuffers_terrain")
                    ? "gbuffers_terrain" : null;
            if (programName == null) continue;
            try {
                translators.put(directory, IrisNvidiumTerrainTranslator.from(
                        model, directory.isEmpty() ? null : directory, programName, optionDefines));
                CaustiqClient.LOGGER.info(
                        "Mapped Nvidium translucent terrain to Iris '{}' for shader pack '{}'{}.",
                        programName, packName, directory.isEmpty() ? "" : " in " + directory);
            } catch (RuntimeException exception) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' {} translucent terrain program '{}' cannot be translated: {}",
                        packName, directory.isEmpty() ? "base" : directory, programName, exception.getMessage());
            }
        }
    }
    private static int parseIntegerProperty(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static float parseFloatProperty(String value, float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) && parsed > 0.0f ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
        boolean executedIrisPasses = executeScheduledIrisPasses();
        executeFinalPass();
        if (!executedIrisPasses) IRIS_PASS_EXECUTOR.publishTerrainOutputs(activePassPlan);
        verifyStrictGpuCi();
    }

    /**
     * Optional fail-closed in-game integration gate. This runs only when a
     * dedicated GPU CI runner launches Minecraft with -Dcaustiq.strictGpuCi=true.
     * It requires observed shader adaptation, Nvidium terrain rasterization,
     * live MRT attachments, Iris fullscreen/compute execution, and real shadow
     * rendering before it emits a PASS marker.
     */
    private static void verifyStrictGpuCi() {
        if (!Boolean.getBoolean("caustiq.strictGpuCi") || strictGpuCiFinished) return;
        strictGpuCiFrames++;
        NvidiumRenderBridge.Snapshot evidence = NvidiumRenderBridge.snapshot();
        if (evidence.shadowNonClearReadbacks() == 0
                && evidence.shadowFrames() >= 15
                && evidence.shadowFrames() % 120 == 15
                && NvidiumShadowRenderer.hasNonClearShadowDepthForStrictCi()) {
            NvidiumRenderBridge.shadowPixelsObserved();
            CaustiqClient.LOGGER.info(
                    "CAUSTIQ_STRICT_EVIDENCE: GPU readback found non-clear depth written by the isolated Nvidium shadow rasterizer.");
            evidence = NvidiumRenderBridge.snapshot();
        }

        boolean baselineReady = evidence.adaptedPrimaryPrograms() > 0
                && evidence.primaryRasterCalls() >= 30 && evidence.primaryRasterRegions() > 0
                && evidence.successfulGbufferAttachments() >= 30
                && evidence.completedGbufferDraws() >= 30
                && evidence.gbufferNonClearReadbacks() > 0
                && evidence.completedSolidPasses() >= 30
                && evidence.adaptedTranslucentPrograms() > 0
                && evidence.adaptedShadowPrograms() > 0 && evidence.shadowFrames() >= 15
                && evidence.shadowNonClearReadbacks() > 0
                && evidence.fullscreenPasses() >= 30 && evidence.computePasses() >= 10
                && evidence.translucentRasterCalls() > 0
                && evidence.completedTranslucentPasses() > 0
                && !evidence.hasUnfinishedPass()
                && NvidiumShadowRenderer.isAvailable()
                && NvidiumShadowRenderer.shadowTexture() != 0;

        if (strictGpuCiBaseline == null) {
            if (baselineReady) {
                strictGpuCiBaseline = evidence;
                strictGpuCiBaselineFrame = strictGpuCiFrames;
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.setYRot(player.getYRot() + 90.0f);
                    player.setXRot(Math.max(-60.0f, Math.min(60.0f, player.getXRot() + 10.0f)));
                    strictGpuCiCameraRotated = true;
                }
                CaustiqClient.LOGGER.info(
                        "CAUSTIQ_STRICT_GPU_CI_BASELINE frames={} primaryRasterCalls={} translucentRasterCalls={} gbufferDraws={} shadowFrames={} fullscreenPasses={} computePasses={} cameraRotated={}",
                        strictGpuCiFrames, evidence.primaryRasterCalls(), evidence.translucentRasterCalls(),
                        evidence.completedGbufferDraws(), evidence.shadowFrames(), evidence.fullscreenPasses(),
                        evidence.computePasses(), strictGpuCiCameraRotated);
            } else if (strictGpuCiFrames >= 1800) {
                strictGpuCiFinished = true;
                CaustiqClient.LOGGER.error(
                        "CAUSTIQ_STRICT_GPU_CI_FAIL phase=initial frames={} evidence={} shadowStatus={}",
                        strictGpuCiFrames, evidence, NvidiumShadowRenderer.shadowMapStatus());
                Minecraft.getInstance().stop();
            }
            return;
        }

        int stableFrames = strictGpuCiFrames - strictGpuCiBaselineFrame;
        if (stableFrames < 600) return;
        NvidiumRenderBridge.Snapshot baseline = strictGpuCiBaseline;
        boolean sustainedAfterCameraChange = strictGpuCiCameraRotated
                && evidence.primaryRasterCalls() >= baseline.primaryRasterCalls() + 600
                && evidence.completedGbufferDraws() >= baseline.completedGbufferDraws() + 600
                && evidence.completedSolidPasses() >= baseline.completedSolidPasses() + 600
                && evidence.translucentRasterCalls() >= baseline.translucentRasterCalls() + 300
                && evidence.completedTranslucentPasses() >= baseline.completedTranslucentPasses() + 300
                && evidence.shadowFrames() >= baseline.shadowFrames() + 300
                && evidence.fullscreenPasses() >= baseline.fullscreenPasses() + 600
                && evidence.computePasses() >= baseline.computePasses() + 100
                && evidence.gbufferNonClearReadbacks() > 0
                && evidence.shadowNonClearReadbacks() > 0
                && !evidence.hasUnfinishedPass()
                && NvidiumShadowRenderer.isAvailable()
                && NvidiumShadowRenderer.shadowTexture() != 0;
        if (sustainedAfterCameraChange) {
            strictGpuCiFinished = true;
            CaustiqClient.LOGGER.info(
                    "CAUSTIQ_STRICT_GPU_CI_PASS frames={} stableFrames={} cameraRotated=true primaryPrograms={} primaryRasterCalls={} primaryRegions={} translucentPrograms={} translucentRasterCalls={} gbufferAttachments={} gbufferDraws={} gbufferPixelReadbacks={} shadowPrograms={} shadowFrames={} shadowDepthReadbacks={} fullscreenPasses={} computePasses={} shadowResolution={}",
                    strictGpuCiFrames, stableFrames, evidence.adaptedPrimaryPrograms(),
                    evidence.primaryRasterCalls(), evidence.primaryRasterRegions(),
                    evidence.adaptedTranslucentPrograms(), evidence.translucentRasterCalls(),
                    evidence.successfulGbufferAttachments(), evidence.completedGbufferDraws(),
                    evidence.gbufferNonClearReadbacks(), evidence.adaptedShadowPrograms(),
                    evidence.shadowFrames(), evidence.shadowNonClearReadbacks(),
                    evidence.fullscreenPasses(), evidence.computePasses(),
                    NvidiumShadowRenderer.shadowResolution());
        } else if (stableFrames >= 1200) {
            strictGpuCiFinished = true;
            CaustiqClient.LOGGER.error(
                    "CAUSTIQ_STRICT_GPU_CI_FAIL phase=stability frames={} stableFrames={} baseline={} current={} shadowStatus={}",
                    strictGpuCiFrames, stableFrames, baseline, evidence,
                    NvidiumShadowRenderer.shadowMapStatus());
            Minecraft.getInstance().stop();
        }
    }

    private static void compileNextProgram() {
        boolean sourcesUnavailable = sources == null;
        boolean allProgramsProcessed = nextProgram >= pending.size();
        if (sourcesUnavailable || allProgramsProcessed) {
            discoveryFinished = true;
            closeSources();
            CaustiqClient.LOGGER.info("Caustiq shader compilation finished: {} linked program(s).", COMPILED.size());
            if (STATUS.snapshot().state() == ShaderRuntimeStatus.State.COMPILING) {
                if (sourcesUnavailable && !allProgramsProcessed) {
                    STATUS.failed("Shader sources became unavailable before all programs were processed.");
                } else {
                    STATUS.complete();
                }
            }
            return;
        }
        int budget = CaustiqConfig.get().compileBudget();
        for (int compiledThisFrame = 0; compiledThisFrame < budget && nextProgram < pending.size(); compiledThisFrame++) {
            ShaderPackProgramCatalog.Program program = pending.get(nextProgram++);
            STATUS.programStarted(program.name());
            try {
                if (program.group() == ShaderPackProgramCatalog.Group.SETUP
                        || program.group() == ShaderPackProgramCatalog.Group.BEGIN
                        || program.group() == ShaderPackProgramCatalog.Group.PREPARE
                        || program.group() == ShaderPackProgramCatalog.Group.DEFERRED
                        || program.group() == ShaderPackProgramCatalog.Group.COMPOSITE
                        || program.group() == ShaderPackProgramCatalog.Group.FINAL
                        || program.group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE) {
                    Set<String> defines = selectedDefines.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    IrisGlProgram linked = new IrisGlProgramCompiler().compile(program, sources::read, defines);
                    IrisGlProgram previous = IRIS_GL_PROGRAMS.put(program.name(), linked);
                    if (previous != null) previous.close();
                }
                CaustiqClient.LOGGER.info("Prepared Iris program '{}' ({} fullscreen programs linked).",
                        program.name(), IRIS_GL_PROGRAMS.size());
            } catch (IOException | RuntimeException exception) {
                STATUS.programFailed(program.name(), exception);
                CaustiqClient.LOGGER.error("Shader program '{}' failed: {}", program.name(), exception.getMessage());
            } finally {
                STATUS.programFinished();
            }
        }
    }

    private static boolean executeScheduledIrisPasses() {
        // Do not execute a partially linked schedule while the per-frame compile budget
        // is still preparing later deferred/composite programs.
        if (!discoveryFinished || !FabricLoader.getInstance().isModLoaded("nvidium")
                || activePassPlan == null || IRIS_GL_PROGRAMS.isEmpty()) return false;
        boolean hasWorldPasses = activePassPlan.passes().stream().anyMatch(scheduled ->
                scheduled.pass().group() == ShaderPackProgramCatalog.Group.SETUP
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.BEGIN
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.PREPARE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.DEFERRED
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.COMPOSITE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.FINAL);
        if (!hasWorldPasses || irisPassFailureLogged) return false;
        try {
            observeNvidiumTerrainBackend();
            IRIS_PASS_EXECUTOR.render(activePassPlan, IRIS_GL_PROGRAMS,
                    Minecraft.getInstance().getMainRenderTarget(), irisShaderResources);
            return true;
        } catch (RuntimeException exception) {
            irisPassFailureLogged = true;
            CaustiqClient.LOGGER.error("CAUSTIQ disabled its Iris multi-pass executor after a render failure.", exception);
            return false;
        }
    }

    private static void closeIrisPrograms() {
        IRIS_GL_PROGRAMS.values().forEach(IrisGlProgram::close);
        IRIS_GL_PROGRAMS.clear();
    }

    private static void executeFinalPass() {
        if (FabricLoader.getInstance().isModLoaded("nvidium") && activePassPlan != null
                && activePassPlan.passes().stream().anyMatch(scheduled ->
                        scheduled.pass().group() == ShaderPackProgramCatalog.Group.SETUP
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.BEGIN
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.PREPARE
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.DEFERRED
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.COMPOSITE
                                || scheduled.pass().group() == ShaderPackProgramCatalog.Group.FINAL)) return;
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
        lastVoxelImageClearFrame = Integer.MIN_VALUE;
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) return;
        minecraft.levelRenderer.allChanged();
        CaustiqClient.LOGGER.info("Requested terrain section rebuild after changing the shader pack block material map.");
    }

    private static int minecraftVersionCode() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow(() -> new IllegalStateException("Minecraft mod metadata is unavailable"))
                .getMetadata().getVersion().getFriendlyString();
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot convert Minecraft version to Iris MC_VERSION: " + version);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return major * 10_000 + minor * 100 + patch;
    }

    private static void beginDiscovery() {
        ShaderPackScanResult result = SCAN_RESULT.get();
        if (result == null || result.packs().isEmpty()) {
            resetStatusForSelection(result);
            discoveryFinished = true;
            return;
        }
        String selectedId = CaustiqConfig.get().selectedPackId();
        ShaderPackDescriptor selected = result.packs().stream()
                .filter(pack -> pack.id().equals(selectedId))
                .findFirst().orElse(null);
        if (selected == null) {
            resetStatusForSelection(result);
            CaustiqClient.LOGGER.info("Shader compilation is idle: select one of the {} discovered packs in Video Settings.",
                    result.packs().size());
            discoveryFinished = true;
            return;
        }
        STATUS.preparing(selected.displayName());
        try {
            sources = ShaderPackSources.open(selected);
            List<String> shaderFiles = sources.listShaderFiles();
            if (shaderFiles.contains("block.properties")) {
                Map<String, Integer> blockIds = new IrisBlockPropertiesParser()
                        .parse(sources.read("block.properties"), minecraftVersionCode());
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
            STATUS.discovering(selected.displayName());
            Map<String, String> defines = new LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : packModel.options().options()) {
                defines.put(option.name(), CaustiqConfig.get().option(
                        selected.id(), option.name(), option.defaultValue()));
            }
            selectedDefines = Map.copyOf(defines);
            String currentDimension = Minecraft.getInstance().level == null ? null
                    : Minecraft.getInstance().level.dimension().identifier().toString();
            IrisShaderPackModel.ProgramSet selectedProgramSet = programSetForDimension(packModel, currentDimension);
            irisShaderResources.close();
            irisShaderResources = IrisShaderResources.load(packModel.properties(), sources, selectedDefines);
            activePassPlan = new IrisTerrainPassScheduler().schedule(packModel, currentDimension, selectedDefines);
            NvidiumGBufferTargets.configureTargets(activePassPlan.targets());
            CaustiqClient.LOGGER.info(
                    "Built Iris pass schedule for '{}': {} ordered program pass(es), {} declared render target(s): {}.",
                    selected.displayName(), activePassPlan.passes().size(), activePassPlan.targets().size(),
                    activePassPlan.orderedProgramNames());
            Set<String> enabledProgramNames = Set.copyOf(activePassPlan.orderedProgramNames());
            List<ShaderPackProgramCatalog.Program> discoveredPrograms = new ArrayList<>();
            List<String> invalidPrograms = new ArrayList<>();
            for (IrisShaderPackModel.Program program : selectedProgramSet.programs().values()) {
                if (!enabledProgramNames.contains(program.name())) continue;
                ShaderPackProgramCatalog.Group group = classifyProgram(program.name());
                ShaderPackProgramCatalog.Program catalogProgram = new ShaderPackProgramCatalog.Program(
                        program.name(), group, 0, program.stages());
                if (!catalogProgram.isExecutableShape()) invalidPrograms.add(program.name());
                discoveredPrograms.add(catalogProgram);
            }
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog.Result(
                    discoveredPrograms, List.of(), invalidPrograms);

            Set<String> unsupportedRequiredFeatures = unsupportedRequiredFeatures(packModel);
            if (!unsupportedRequiredFeatures.isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires Iris feature flag(s) CAUSTIQ cannot provide: {}; refusing partial activation.",
                        selected.displayName(), unsupportedRequiredFeatures);
                STATUS.failed("This pack requires unsupported Iris features: " + unsupportedRequiredFeatures
                        + ". Choose a compatible pack or remove those requirements.");
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
                STATUS.failed("This pack uses unsupported Iris features: " + packModel.diagnostics()
                        + ". See latest.log for details.");
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            IrisShaderPackModel.Program finalProgram = enabledProgramNames.contains("final")
                    ? selectedProgramSet.programs().get("final") : null;
            if (finalProgram != null && (!finalProgram.hasVertexAndFragment()
                    || finalProgram.stages().size() != 2)) {
                CaustiqClient.LOGGER.warn("Shader pack '{}' has an unsupported final program stage shape.",
                        selected.displayName());
                STATUS.failed("The selected pack's final program has an unsupported shader-stage layout.");
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            Set<ShaderPackProgramCatalog.Group> supportedGroups = Set.of(
                    ShaderPackProgramCatalog.Group.SETUP,
                    ShaderPackProgramCatalog.Group.BEGIN,
                    ShaderPackProgramCatalog.Group.PREPARE,
                    ShaderPackProgramCatalog.Group.GBUFFERS,
                    ShaderPackProgramCatalog.Group.SHADOW,
                    ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE,
                    ShaderPackProgramCatalog.Group.DEFERRED,
                    ShaderPackProgramCatalog.Group.COMPOSITE,
                    ShaderPackProgramCatalog.Group.FINAL);
            List<String> unsupportedGroups = catalog.programs().stream()
                    .filter(program -> !supportedGroups.contains(program.group())
                            || (program.group() == ShaderPackProgramCatalog.Group.SHADOW
                                && (!program.stages().containsKey(ShaderPackProgramCatalog.Stage.VERTEX)
                                    || !program.stages().containsKey(ShaderPackProgramCatalog.Stage.FRAGMENT)))
                            || ((program.group() == ShaderPackProgramCatalog.Group.SETUP
                                    || program.group() == ShaderPackProgramCatalog.Group.BEGIN
                                    || program.group() == ShaderPackProgramCatalog.Group.PREPARE
                                    || program.group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE)
                                && !(program.stages().size() == 1
                                    && program.stages().containsKey(ShaderPackProgramCatalog.Stage.COMPUTE)
                                    || program.stages().size() == 2
                                    && program.stages().containsKey(ShaderPackProgramCatalog.Stage.VERTEX)
                                    && program.stages().containsKey(ShaderPackProgramCatalog.Stage.FRAGMENT))))
                    .map(program -> program.name() + " (" + program.group() + ", unsupported stage shape)")
                    .toList();
            if (!unsupportedGroups.isEmpty()) {
                CaustiqClient.LOGGER.warn("Shader pack '{}' requires unimplemented Iris pass groups {}; refusing partial execution.",
                        selected.displayName(), unsupportedGroups);
                STATUS.failed("This pack uses unsupported shader pass groups: " + unsupportedGroups
                        + ". See latest.log for details.");
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            boolean hasMultiPass = catalog.programs().stream().anyMatch(program ->
                    program.group() == ShaderPackProgramCatalog.Group.SETUP
                            || program.group() == ShaderPackProgramCatalog.Group.BEGIN
                            || program.group() == ShaderPackProgramCatalog.Group.PREPARE
                            || program.group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE
                            || program.group() == ShaderPackProgramCatalog.Group.FINAL
                            || program.group() == ShaderPackProgramCatalog.Group.DEFERRED
                            || program.group() == ShaderPackProgramCatalog.Group.COMPOSITE);
            if (hasMultiPass && !FabricLoader.getInstance().isModLoaded("nvidium")) {
                CaustiqClient.LOGGER.warn("Shader pack '{}' requires Nvidium's terrain MRT path for its deferred/composite passes.",
                        selected.displayName());
                STATUS.failed("This pack requires Nvidium's terrain render-target path. Install the supported Nvidium version.");
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            pending = catalog.programs();
            STATUS.beginCompilation(pending.size());

            CaustiqClient.LOGGER.info(
                    "Parsed Iris pack '{}': {} base program(s), {} dimension override(s), {} required feature(s), {} preprocessed shader stage(s), {} option define(s).",
                    selected.displayName(), packModel.base().programs().size(),
                    packModel.dimensionOverrides().size(), packModel.requiredFeatures().size(),
                    packModel.preprocessedSources().size(), selectedDefines.size());
            if (pending.isEmpty()) {
                discoveryFinished = true;
                closeSources();
                STATUS.complete();
            }
        } catch (IOException | RuntimeException exception) {
            irisShaderResources.close();
            irisShaderResources = IrisShaderResources.empty();
            CaustiqClient.LOGGER.error("Unable to prepare shader pack '{}': {}", selected.displayName(), exception.getMessage());
            STATUS.failed("Unable to prepare the selected shader pack (" + safeFailureSummary(exception)
                    + "). See latest.log for details, then use Reload to retry.");
            discoveryFinished = true;
            closeSources();
        }
    }

    private static Set<String> unsupportedRequiredFeatures(IrisShaderPackModel model) {
        return model.requiredFeatures().stream()
                .map(feature -> feature.toUpperCase(java.util.Locale.ROOT))
                .filter(feature -> !SUPPORTED_REQUIRED_IRIS_FEATURES.contains(feature))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static IrisShaderPackModel.ProgramSet programSetForDimension(
            IrisShaderPackModel model, String dimensionId) {
        if (dimensionId != null && !dimensionId.isBlank()) {
            String directory = model.dimensionMappings().getOrDefault(
                    dimensionId, builtInDimensionDirectory(dimensionId));
            IrisShaderPackModel.ProgramSet override = model.dimensionOverrides().get(directory);
            if (override != null) return override;
        }
        return model.base();
    }

    private static ShaderPackProgramCatalog.Group classifyProgram(String name) {
        // Keep runtime activation consistent with discovery and scheduling.
        // In particular, Photon uses the exact program name "clrwl_gbuffers";
        // after removing its prefix this is "gbuffers", not "gbuffers_*".
        return ShaderPackProgramCatalog.Group.classify(name);
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
        PRELOAD_STATUS_GENERATION.incrementAndGet();
        PRELOADED_PACK.invalidate();
        NvidiumGBufferTargets.destroy();
        SCAN_RESULT.set(null);
    }
}
