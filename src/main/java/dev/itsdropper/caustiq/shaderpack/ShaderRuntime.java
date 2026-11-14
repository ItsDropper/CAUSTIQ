package dev.itsdropper.caustiq.shaderpack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.itsdropper.caustiq.CaustiqClient;
import dev.itsdropper.caustiq.CaustiqConfig;
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
    private static Map<String, String> selectedDefines = Map.of();
    private static int nextProgram;
    private static boolean postProcessFailureLogged;

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
        selectedDefines = Map.of();
        nextProgram = 0;
        discoveryStarted = false;
        discoveryFinished = false;
        postProcessFailureLogged = false;
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
        try {
            RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
            POST_PROCESS.render(mainTarget, finalProgram);
        } catch (RuntimeException exception) {
            postProcessFailureLogged = true;
            CaustiqClient.LOGGER.error("Caustiq disabled its final pass after a render-target failure.", exception);
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
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog().discover(sources);
            pending = catalog.programs();

            Map<String, String> defines = new LinkedHashMap<>();
            try {
                String properties;
                try {
                    properties = sources.read("shaders.properties");
                } catch (IOException missingProperties) {
                    properties = "";
                }
                ShaderPackMetadata metadata = ShaderPackMetadata.parse(properties, sources);
                for (ShaderPackMetadata.Option option : metadata.options()) {
                    defines.put(option.name(), CaustiqConfig.get().option(
                            selected.id(), option.name(), option.defaultValue()));
                }
            } catch (IOException | IllegalArgumentException metadataFailure) {
                CaustiqClient.LOGGER.warn("Unable to parse shader options for '{}'; compiling with pack defaults: {}",
                        selected.displayName(), metadataFailure.getMessage());
            }
            selectedDefines = Map.copyOf(defines);

            CaustiqClient.LOGGER.info("Preparing '{}': {} conventional program(s), {} unmatched/unsupported stage(s), {} option define(s).",
                    selected.displayName(), pending.size(), catalog.ignoredStages().size(), selectedDefines.size());
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
        SCAN_RESULT.set(null);
    }
}
