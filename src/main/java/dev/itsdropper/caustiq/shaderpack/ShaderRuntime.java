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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderRuntime {
    private static final Set<String> SUPPORTED_REQUIRED_IRIS_FEATURES = Set.of("CUSTOM_IMAGES");
    private static final AtomicReference<ShaderPackScanResult> SCAN_RESULT = new AtomicReference<>();
    private static final List<CompiledShaderProgram> COMPILED = new ArrayList<>();
    private static final ShaderPostProcessPass POST_PROCESS = new ShaderPostProcessPass();
    private static final IrisGlPassExecutor IRIS_PASS_EXECUTOR = new IrisGlPassExecutor();
    private static IrisShaderResources irisShaderResources = IrisShaderResources.empty();
    private static final Map<String, IrisGlProgram> IRIS_GL_PROGRAMS = new LinkedHashMap<>();
    private static boolean discoveryStarted, discoveryFinished;
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
    public static void bindTerrainCustomTextures(int program) {
        try {
            NvidiumIrisTextureBridge.bindCustomTextures(irisShaderResources, program);
        } catch (RuntimeException exception) {
            CaustiqClient.LOGGER.warn("Could not bind custom Iris terrain textures: {}", exception.getMessage());
        }
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
    private static void prepareSelectedNvidiumTerrainAdapter() {
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

        try (ShaderPackSources selectedSources = ShaderPackSources.open(selected)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(selectedSources);
            Map<String, String> optionDefines = new LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : model.options().options()) {
                optionDefines.put(option.name(), CaustiqConfig.get().option(
                        selected.id(), option.name(), option.defaultValue()));
            }
            IrisShaderResources loadedResources = IrisShaderResources.load(
                    model.properties(), selectedSources, optionDefines);
            irisShaderResources.close();
            irisShaderResources = loadedResources;
            Set<String> unsupportedRequiredFeatures = unsupportedRequiredFeatures(model);
            if (!unsupportedRequiredFeatures.isEmpty()) {
                CaustiqClient.LOGGER.warn(
                        "Shader pack '{}' requires Iris feature flag(s) CAUSTIQ cannot provide: {}; Nvidium terrain translation remains disabled.",
                        selected.displayName(), unsupportedRequiredFeatures);
                return;
            }

            configureShadowRenderer(model, null);

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
                return;
            }

            preparedPackModel = model;
            preparedPackId = selected.id();
            preparedTerrainTranslators = Map.copyOf(translators);
            preparedTranslucentTerrainTranslators = Map.copyOf(translucentTranslators);
            preparedUnsupportedTerrainDimensions = Set.copyOf(unsupportedDimensions);
            preparedTerrainPlan = new IrisTerrainPassScheduler().schedule(model, null);
            preparedDimensionMappings = model.dimensionMappings();

            CaustiqClient.LOGGER.info(
                    "Prepared shader pack '{}' for Nvidium terrain translation: {} dimension program(s), {} unsupported dimension program(s).",
                    selected.displayName(), translators.size(), unsupportedDimensions.size());
            CaustiqClient.LOGGER.info(
                    "Prepared {} separate Iris translucent terrain program(s) for Nvidium.",
                    translucentTranslators.size());
            activateTerrainAdapterForCurrentWorld();
        } catch (IOException | RuntimeException exception) {
            irisShaderResources.close();
            irisShaderResources = IrisShaderResources.empty();
            NvidiumShaderProgramBridge.unregisterTerrainAdapter();
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
        String dimensionId = minecraft.level == null
                ? "" : minecraft.level.dimension().identifier().toString();
        String directory = dimensionId.isEmpty() ? ""
                : preparedDimensionMappings.getOrDefault(
                        dimensionId, builtInDimensionDirectory(dimensionId));

        if (preparedUnsupportedTerrainDimensions.contains(directory)) {
            deactivateTerrainAdapter();
            return false;
        }

        IrisNvidiumTerrainTranslator translator = preparedTerrainTranslators.get(directory);
        String adapterKey = directory;
        if (translator == null) {
            if (preparedUnsupportedTerrainDimensions.contains("")) {
                deactivateTerrainAdapter();
                return false;
            }
            translator = preparedTerrainTranslators.get("");
            adapterKey = "";
        }
        if (translator == null) {
            deactivateTerrainAdapter();
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

    private static void deactivateTerrainAdapter() {
        if (!preparedTerrainAdapterRegistered) return;
        NvidiumShaderProgramBridge.unregisterTerrainAdapter();
        NvidiumGBufferTargets.resetOutputRouting();
        NvidiumGBufferTargets.destroy();
        preparedTerrainAdapterRegistered = false;
        activeTerrainDimensionKey = "";
        NvidiumShadowRenderer.configure(false, 2048, 128.0f);
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
            NvidiumShadowRenderer.configure(false, 2048, 128.0f);
            CaustiqClient.LOGGER.info(
                    "Shader pack has no translatable Iris shadow terrain program; Nvidium shadow rendering remains disabled.");
            return;
        }

        try {
            IrisNvidiumTerrainTranslator solid = IrisNvidiumTerrainTranslator.from(
                    model, dimensionId, solidProgram, optionDefines);
            IrisNvidiumTerrainTranslator cutout = IrisNvidiumTerrainTranslator.from(
                    model, dimensionId, cutoutProgram == null ? solidProgram : cutoutProgram, optionDefines);
            IrisNvidiumTerrainTranslator translucent = IrisNvidiumTerrainTranslator.from(
                    model, dimensionId, translucentProgram == null ? solidProgram : translucentProgram, optionDefines);
            NvidiumShaderProgramBridge.registerShadowTerrainAdapter(solid::adapt);
            NvidiumShaderProgramBridge.registerShadowCutoutTerrainAdapter(cutout::adapt);
            NvidiumShaderProgramBridge.registerShadowTranslucentTerrainAdapter(translucent::adapt);
        } catch (RuntimeException exception) {
            NvidiumShadowRenderer.configure(false, 2048, 128.0f);
            CaustiqClient.LOGGER.warn(
                    "Selected Iris shadow terrain program set ({}, {}, {}) cannot be translated for Nvidium: {}",
                    solidProgram, cutoutProgram, translucentProgram, exception.getMessage());
            return;
        }

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

    private static String selectShadowProgram(
            IrisShaderPackModel model, String dimensionId, String... candidates) {
        String directory = dimensionId == null || dimensionId.isBlank()
                ? "" : builtInDimensionDirectory(dimensionId);
        IrisShaderPackModel.ProgramSet dimensionSet = model.dimensionOverrides().get(directory);
        // Prefer any matching variant in the active dimension before falling
        // back to the base pack or another dimension's program inventory.
        if (dimensionSet != null) {
            for (String candidate : candidates) {
                if (dimensionSet.programs().containsKey(candidate)) return candidate;
            }
        }
        for (String candidate : candidates) {
            if (model.base().programs().containsKey(candidate)) return candidate;
        }
        for (String candidate : candidates) {
            if (model.dimensionOverrides().values().stream()
                    .anyMatch(programSet -> programSet.programs().containsKey(candidate))) return candidate;
        }
        return null;
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
        executeScheduledIrisPasses();
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
                CaustiqClient.LOGGER.error("Shader program '{}' failed: {}", program.name(), exception.getMessage());
            }
        }
    }

    private static void executeScheduledIrisPasses() {
        // Do not execute a partially linked schedule while the per-frame compile budget
        // is still preparing later deferred/composite programs.
        if (!discoveryFinished || !FabricLoader.getInstance().isModLoaded("nvidium")
                || activePassPlan == null || IRIS_GL_PROGRAMS.isEmpty()) return;
        boolean hasWorldPasses = activePassPlan.passes().stream().anyMatch(scheduled ->
                scheduled.pass().group() == ShaderPackProgramCatalog.Group.SETUP
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.BEGIN
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.PREPARE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.DEFERRED
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.COMPOSITE
                        || scheduled.pass().group() == ShaderPackProgramCatalog.Group.FINAL);
        if (!hasWorldPasses || irisPassFailureLogged) return;
        try {
            observeNvidiumTerrainBackend();
            IRIS_PASS_EXECUTOR.render(activePassPlan, IRIS_GL_PROGRAMS,
                    Minecraft.getInstance().getMainRenderTarget(), irisShaderResources);
        } catch (RuntimeException exception) {
            irisPassFailureLogged = true;
            CaustiqClient.LOGGER.error("CAUSTIQ disabled its Iris multi-pass executor after a render failure.", exception);
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
            IrisShaderPackModel.Program finalProgram = enabledProgramNames.contains("final")
                    ? selectedProgramSet.programs().get("final") : null;
            if (finalProgram != null && (!finalProgram.hasVertexAndFragment()
                    || finalProgram.stages().size() != 2)) {
                CaustiqClient.LOGGER.warn("Shader pack '{}' has an unsupported final program stage shape.",
                        selected.displayName());
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
                pending = List.of();
                discoveryFinished = true;
                closeSources();
                return;
            }
            pending = catalog.programs();

            CaustiqClient.LOGGER.info(
                    "Parsed Iris pack '{}': {} base program(s), {} dimension override(s), {} required feature(s), {} preprocessed shader stage(s), {} option define(s).",
                    selected.displayName(), packModel.base().programs().size(),
                    packModel.dimensionOverrides().size(), packModel.requiredFeatures().size(),
                    packModel.preprocessedSources().size(), selectedDefines.size());
            if (pending.isEmpty()) {
                discoveryFinished = true;
                closeSources();
            }
        } catch (IOException | RuntimeException exception) {
            irisShaderResources.close();
            irisShaderResources = IrisShaderResources.empty();
            CaustiqClient.LOGGER.error("Unable to prepare shader pack '{}': {}", selected.displayName(), exception.getMessage());
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
        NvidiumGBufferTargets.destroy();
        SCAN_RESULT.set(null);
    }
}
