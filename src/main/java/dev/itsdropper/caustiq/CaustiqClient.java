package dev.itsdropper.caustiq;

import dev.itsdropper.caustiq.gui.CaustiqSettingsScreen;
import dev.itsdropper.caustiq.shaderpack.ShaderPackIssue;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanResult;
import dev.itsdropper.caustiq.shaderpack.ShaderPackScanner;
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
import java.util.concurrent.CompletableFuture;

public final class CaustiqClient implements ClientModInitializer {
    public static final String MOD_ID = "caustiq";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String SHADER_PACK_DIRECTORY = "shaderpacks";
    private static final int MAX_LOGGED_ISSUES = 10;
    private static volatile ShaderPackScanResult scanResult;
    private static Path shaderPackDirectory;

    @Override
    public void onInitializeClient() {
        CaustiqConfig.get();
        shaderPackDirectory = FabricLoader.getInstance().getGameDir().resolve(SHADER_PACK_DIRECTORY);
        try {
            Files.createDirectories(shaderPackDirectory);
        } catch (IOException exception) {
            LOGGER.error("Unable to create shader-pack directory at {}", shaderPackDirectory, exception);
            return;
        }
        LOGGER.info("Caustiq initialized for Minecraft 1.21.11. Shader-pack directory: {}",
                shaderPackDirectory.toAbsolutePath());
        refreshShaderPacks();
    }

    public static ShaderPackScanResult scanResult() { return scanResult; }

    public static void refreshShaderPacks() {
        Path directory = shaderPackDirectory;
        if (directory == null) return;
        CompletableFuture.supplyAsync(() -> new ShaderPackScanner().scan(directory))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Shader-pack discovery failed for {}", directory.toAbsolutePath(), failure);
                        return;
                    }
                    Minecraft.getInstance().execute(() -> {
                        ShaderRuntime.requestReload();
                        scanResult = result;
                        ShaderRuntime.publishScanResult(result);
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
                        if (Minecraft.getInstance().screen instanceof CaustiqSettingsScreen settingsScreen) {
                            settingsScreen.refreshView();
                        }
                    });
                });
    }
}
