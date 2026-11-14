package dev.itsdropper.caustiq;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client entrypoint for Caustiq.
 *
 * <p>Rendering hooks and shader-pack loading are intentionally kept out of
 * initialization until their runtime contracts are implemented.</p>
 */
public final class CaustiqClient implements ClientModInitializer {
    public static final String MOD_ID = "caustiq";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String SHADER_PACK_DIRECTORY = "shaderpacks";

    @Override
    public void onInitializeClient() {
        Path shaderPackDirectory = FabricLoader.getInstance()
                .getGameDir()
                .resolve(SHADER_PACK_DIRECTORY);

        try {
            Files.createDirectories(shaderPackDirectory);
        } catch (IOException exception) {
            LOGGER.error("Unable to create shader-pack directory at {}", shaderPackDirectory, exception);
            return;
        }

        LOGGER.info("Caustiq initialized for Minecraft 1.21.11.");
        LOGGER.info("Shader-pack directory: {}", shaderPackDirectory.toAbsolutePath());
    }
}
