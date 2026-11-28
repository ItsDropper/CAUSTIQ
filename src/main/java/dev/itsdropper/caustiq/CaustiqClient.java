package dev.itsdropper.caustiq;

import dev.itsdropper.caustiq.gui.CaustiqSettingsScreen;
import dev.itsdropper.caustiq.gui.ShaderPackBrowserScreen;
import dev.itsdropper.caustiq.gui.ShaderPackDiagnosticsScreen;
import dev.itsdropper.caustiq.integration.RendererEnvironment;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumCompatibility;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumShaderProgramBridge;
import dev.itsdropper.caustiq.integration.nvidium.NvidiumTerrainVaryingAdapter;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackRefreshStatus;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanner;
import dev.itsdropper.caustiq.shaderpack.ShaderPackStartupScan;
import dev.itsdropper.caustiq.shaderpack.ShaderRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaustiqClient implements ClientModInitializer {
    public static final String MOD_ID = "caustiq";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String SHADER_PACK_DIRECTORY = "shaderpacks";
    private static final int MAX_LOGGED_ISSUES = 10;
    private static volatile ShaderPackScanResult scanResult;
    private static volatile ShaderPackRefreshStatus refreshStatus = ShaderPackRefreshStatus.idle();
    private static volatile AtomicBoolean activeScanCancellation;
    private static volatile ShaderPackStartupScan startupScan;
    private static Path shaderPackDirectory;

    @Override
    public void onInitializeClient() {
        CaustiqConfig.get();
        shaderPackDirectory = FabricLoader.getInstance().getGameDir().resolve(SHADER_PACK_DIRECTORY);
        try {
            Files.createDirectories(shaderPackDirectory);
        } catch (IOException exception) {
            LOGGER.error("Unable to create shader-pack directory at {}", shaderPackDirectory, exception);
            refreshStatus = ShaderPackRefreshStatus.failed(ShaderPackIssue.sanitizeDisplayText(exception.getMessage()));
            return;
        }
        RendererEnvironment renderer = RendererEnvironment.detect(FabricLoader.getInstance()::isModLoaded);
        LOGGER.info("Renderer stack detected: {} (Sodium: {}, Nvidium: {}).",
                renderer.stack(), renderer.sodiumLoaded(), renderer.nvidiumLoaded());
        NvidiumCompatibility.Status nvidiumStatus = NvidiumCompatibility.inspect(FabricLoader.getInstance()::isModLoaded);
        LOGGER.info("Nvidium distribution dependency check: {}.", nvidiumStatus);
        if (renderer.nvidiumLoaded()) {
            if (NvidiumCompatibility.requestExtendedVertexFormat()) {
                NvidiumShaderProgramBridge.registerNativeSourceAdapter(NvidiumTerrainVaryingAdapter::adapt);
                LOGGER.info("Requested Nvidium terrain vertex ABI {}. The Sodium-compatible 40-byte format will be committed after Nvidium's OpenGL capability check.",
                        NvidiumTerrainVaryingAdapter.PROFILE_ID);
            } else {
                LOGGER.warn("CAUSTIQ's Nvidium terrain adapter is not requested (CAUSTIQ is disabled or Nvidium is force-disabled). Nvidium's native terrain shader and vertex format will remain untouched.");
            }
        }
        LOGGER.info("Caustiq initialized for Minecraft 1.21.11. Shader-pack directory: {}",
                shaderPackDirectory.toAbsolutePath());
        scanShaderPacksAtStartup();
    }

    public static ShaderPackScanResult scanResult() { return scanResult; }
    public static ShaderPackRefreshStatus refreshStatus() { return refreshStatus; }

    private static void logScanResult(ShaderPackScanResult result, Path directory) {
        LOGGER.info("Shader-pack discovery completed: {} valid pack(s), {} rejected candidate(s).",
                result.packs().size(), result.issues().size());
        result.packs().forEach(pack -> LOGGER.info(
                "Found shader pack '{}' ({}, {} shader source file(s)).",
                pack.displayName(), pack.sourceType().name().toLowerCase(Locale.ROOT),
                pack.shaderFileCount()));
        result.issues().stream().limit(MAX_LOGGED_ISSUES)
                .map(ShaderPackIssue::toString)
                .forEach(issue -> LOGGER.warn("Shader-pack candidate rejected: {}", issue));
        if (result.issues().size() > MAX_LOGGED_ISSUES) {
            LOGGER.warn("{} additional shader-pack issue(s) were omitted from the log.",
                    result.issues().size() - MAX_LOGGED_ISSUES);
        }
    }

    /**
     * The initial scan is intentionally completed before the first world is
     * entered: Nvidium decides whether it can run with Iris while constructing
     * its section manager, before the first world-render callback.
     */
    private static void scanShaderPacksAtStartup() {
        Path directory = shaderPackDirectory;
        refreshStatus = ShaderPackRefreshStatus.startupScanning();
        ShaderPackStartupScan scan = ShaderPackStartupScan.start(() -> {
            ShaderPackScanResult result = new ShaderPackScanner().scan(directory, progress -> {
                synchronized (CaustiqClient.class) {
                    if (refreshStatus.state() == ShaderPackRefreshStatus.State.STARTUP_SCANNING) {
                        refreshStatus = ShaderPackRefreshStatus.startupScanning(progress);
                    }
                }
            });
            try {
                // Start CPU-only parsing while the title screen is loading. GL-backed
                // resource creation remains at Nvidium's existing pre-world safety check.
                ShaderRuntime.preloadSelectedPackModelForStartup(result);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not start background parsing for the selected startup pack.", exception);
            }
            return result;
        });
        startupScan = scan;
        scan.publishWhenReady(
                command -> Minecraft.getInstance().execute(command),
                outcome -> publishStartupScan(scan, outcome, directory));
    }

    /** Waits for discovery only at Nvidium's existing pre-world Iris compatibility check. */
    public static void awaitStartupScan() {
        ShaderPackStartupScan scan = startupScan;
        if (scan != null) {
            scan.awaitAndPublish(outcome -> publishStartupScan(scan, outcome, shaderPackDirectory));
        }
    }

    private static void publishStartupScan(
            ShaderPackStartupScan scan,
            ShaderPackStartupScan.Outcome outcome,
            Path directory
    ) {
        synchronized (CaustiqClient.class) {
            if (startupScan != scan) return;
            startupScan = null;
            if (outcome.failure() != null) {
                scanResult = null;
                LOGGER.error("Shader-pack discovery failed for {}", directory.toAbsolutePath(), outcome.failure());
                refreshStatus = ShaderPackRefreshStatus.failed(failureSummary(outcome.failure()));
            } else {
                ShaderPackScanResult result = outcome.result();
                scanResult = result;
                try {
                    // Publish without GL preparation; the Nvidium compatibility hook
                    // performs that once, at its unchanged pre-world decision point.
                    ShaderRuntime.publishStartupScanResult(result);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not publish the startup shader-pack scan to the runtime.", exception);
                }
                refreshStatus = ShaderPackRefreshStatus.complete(result);
                logScanResult(result, directory);
            }
        }
        refreshSettingsScreen();
    }

    public static synchronized void refreshShaderPacks() {
        if (refreshStatus.isScanning()) return;
        refreshStatus = ShaderPackRefreshStatus.scanning();
        refreshSettingsScreen();

        Path directory = shaderPackDirectory;
        if (directory == null) {
            refreshStatus = ShaderPackRefreshStatus.failed("Shader-pack directory is not available.");
            refreshSettingsScreen();
            return;
        }

        AtomicBoolean cancellation = new AtomicBoolean();
        activeScanCancellation = cancellation;
        CompletableFuture.supplyAsync(() -> new ShaderPackScanner().scan(directory,
                        progress -> {
                            synchronized (CaustiqClient.class) {
                                if (activeScanCancellation == cancellation && !cancellation.get()
                                        && refreshStatus.state() == ShaderPackRefreshStatus.State.SCANNING) {
                                    refreshStatus = ShaderPackRefreshStatus.scanning(progress);
                                }
                            }
                        },
                        cancellation::get))
                .whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
                    synchronized (CaustiqClient.class) {
                        if (activeScanCancellation == cancellation) {
                            activeScanCancellation = null;
                        }
                        if (cancellation.get() || isCancellation(failure)) {
                            refreshStatus = ShaderPackRefreshStatus.cancelled();
                        } else if (failure != null) {
                            LOGGER.error("Shader-pack discovery failed for {}", directory.toAbsolutePath(), failure);
                            refreshStatus = ShaderPackRefreshStatus.failed(failureSummary(failure));
                        } else {
                            ShaderRuntime.requestReload();
                            scanResult = result;
                            ShaderRuntime.publishScanResult(result);
                            refreshStatus = ShaderPackRefreshStatus.complete(result);
                            logScanResult(result, directory);
                        }
                    }
                    refreshSettingsScreen();
                }));
    }

    /** Starts a scan when idle, or requests cancellation while a scan is active. */
    public static synchronized void toggleShaderPackScan() {
        if (refreshStatus.isCancellable()) {
            cancelShaderPackScan();
        } else if (!refreshStatus.isScanning()) {
            refreshShaderPacks();
        }
    }

    /** Requests cooperative cancellation; the previous completed scan remains published. */
    public static synchronized void cancelShaderPackScan() {
        if (!refreshStatus.isCancellable()) return;
        AtomicBoolean cancellation = activeScanCancellation;
        if (cancellation == null) return;

        cancellation.set(true);
        refreshStatus = ShaderPackRefreshStatus.cancelling(refreshStatus.progress());
        refreshSettingsScreen();
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof CancellationException) return true;
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return false;
    }

    private static String failureSummary(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return ShaderPackIssue.sanitizeDisplayText(
                message == null || message.isBlank() ? cause.getClass().getSimpleName() : message
        );
    }

    private static void refreshSettingsScreen() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof CaustiqSettingsScreen settingsScreen) {
                settingsScreen.refreshView();
            } else if (client.screen instanceof ShaderPackDiagnosticsScreen diagnosticsScreen) {
                diagnosticsScreen.refreshView();
            } else if (client.screen instanceof ShaderPackBrowserScreen browserScreen) {
                browserScreen.refreshView();
            }
        });
    }
}
