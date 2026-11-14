package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.CaustiqClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Render-thread coordinator that incrementally compiles a selected shader pack. */
public final class ShaderRuntime {
    private static final AtomicReference<ShaderPackScanResult> SCAN_RESULT = new AtomicReference<>();
    private static final List<CompiledShaderProgram> COMPILED = new ArrayList<>();
    private static boolean discoveryStarted, discoveryFinished;
    private static ShaderPackSources sources;
    private static List<ShaderPackProgramCatalog.Program> pending = List.of();
    private static int nextProgram;

    private ShaderRuntime() { }
    public static void publishScanResult(ShaderPackScanResult result) { SCAN_RESULT.set(result); }

    /** Invoked by GameRenderer while the OpenGL context is current. */
    public static void onWorldRender() {
        if (discoveryFinished) return;
        if (!discoveryStarted) {
            if (SCAN_RESULT.get() == null) return;
            discoveryStarted = true;
            beginDiscovery();
            return;
        }
        if (sources == null || nextProgram >= pending.size()) {
            discoveryFinished = true;
            closeSources();
            CaustiqClient.LOGGER.info("Caustiq GLSL compilation finished: {} linked program(s).", COMPILED.size());
            return;
        }
        ShaderPackProgramCatalog.Program program = pending.get(nextProgram++);
        try {
            COMPILED.add(new ShaderProgramCompiler().compile(program, sources));
            CaustiqClient.LOGGER.info("Compiled shader program '{}' ({} linked).", program.name(), COMPILED.size());
        } catch (IOException | RuntimeException exception) {
            CaustiqClient.LOGGER.error("Shader program '{}' failed: {}", program.name(), exception.getMessage());
        }
    }

    private static void beginDiscovery() {
        ShaderPackScanResult result = SCAN_RESULT.get();
        if (result == null || result.packs().isEmpty()) {
            discoveryFinished = true;
            return;
        }
        // Never silently choose between multiple installed packs.
        if (result.packs().size() != 1) {
            CaustiqClient.LOGGER.info("Found {} shader packs; compilation is idle until a pack is selected.", result.packs().size());
            discoveryFinished = true;
            return;
        }
        ShaderPackDescriptor selected = result.packs().getFirst();
        try {
            sources = ShaderPackSources.open(selected);
            ShaderPackProgramCatalog.Result catalog = new ShaderPackProgramCatalog().discover(sources);
            pending = catalog.programs();
            CaustiqClient.LOGGER.info("Preparing '{}': {} conventional program(s), {} unmatched/unsupported stage(s).",
                    selected.displayName(), pending.size(), catalog.ignoredStages().size());
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
            catch (IOException exception) { CaustiqClient.LOGGER.warn("Unable to close shader sources: {}", exception.getMessage()); }
            sources = null;
        }
    }

    /** Releases GPU objects during renderer shutdown. Must run on the render thread. */
    public static void shutdown() {
        closeSources();
        COMPILED.forEach(CompiledShaderProgram::close);
        COMPILED.clear();
        pending = List.of();
        nextProgram = 0;
        discoveryStarted = false;
        discoveryFinished = false;
        SCAN_RESULT.set(null);
    }
}
