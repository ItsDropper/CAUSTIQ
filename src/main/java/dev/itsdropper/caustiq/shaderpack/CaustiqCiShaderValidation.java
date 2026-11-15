package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.CaustiqClient;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Render-thread validation hook used by the dedicated Photon CI workflow.
 * It runs inside Minecraft's real OpenGL context, independently of Nvidium.
 */
public final class CaustiqCiShaderValidation {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private CaustiqCiShaderValidation() { }

    public static void runFromEnvironment() {
        if (!"1".equals(System.getenv("CAUSTIQ_CI_SHADER_VALIDATION"))
                || !STARTED.compareAndSet(false, true)) return;

        String configuredPath = System.getenv("CAUSTIQ_CI_SHADERPACK");
        try {
            if (configuredPath == null || configuredPath.isBlank()) {
                throw new IOException("CAUSTIQ_CI_SHADERPACK is not set.");
            }
            if (FabricLoader.getInstance().isModLoaded("nvidium")) {
                throw new IOException("Nvidium must not be loaded in the shader-only validation job.");
            }
            CaustiqClient.LOGGER.info("Photon CI renderer isolation confirmed: Nvidium is absent.");
            int compiled = validate(Path.of(configuredPath));
            captureMinecraftFrame();
            String result = "CAUSTIQ_CI_SHADER_VALIDATION_PASS programs=" + compiled;
            CaustiqClient.LOGGER.info(result);
            System.out.println(result);
        } catch (Exception exception) {
            try {
                captureMinecraftFrame();
            } catch (Exception screenshotFailure) {
                exception.addSuppressed(screenshotFailure);
            }
            String result = "CAUSTIQ_CI_SHADER_VALIDATION_FAIL " + exception.getMessage();
            CaustiqClient.LOGGER.error(result, exception);
            System.err.println(result);
        }
    }


    private static void captureMinecraftFrame() throws IOException {
        String configuredPath = System.getenv("CAUSTIQ_CI_SCREENSHOT");
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IOException("CAUSTIQ_CI_SCREENSHOT is not set.");
        }

        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        int x = viewport.get(0);
        int y = viewport.get(1);
        int width = viewport.get(2);
        int height = viewport.get(3);
        if (width <= 0 || height <= 0) {
            throw new IOException("Minecraft exposed an empty OpenGL viewport: " + width + "x" + height);
        }

        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
        int previousPackAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
            GL11C.glReadBuffer(GL11C.GL_BACK);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glFinish();

            ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            GL11C.glReadPixels(x, y, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int offset = (row * width + column) * 4;
                    int red = Byte.toUnsignedInt(pixels.get(offset));
                    int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                    int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                    int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                    image.setRGB(column, height - row - 1,
                            (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
            Path output = Path.of(configuredPath).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IOException("No PNG image writer is available.");
            }
            CaustiqClient.LOGGER.info("Captured actual Minecraft framebuffer screenshot: {} ({}x{}).",
                    output, width, height);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11C.glReadBuffer(previousReadBuffer);
        }
    }

    static int validate(Path shaderPackPath) throws IOException {
        Path packPath = shaderPackPath.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(packPath)) {
            throw new IOException("Photon shader-pack directory does not exist: " + packPath);
        }
        Path parent = packPath.getParent();
        if (parent == null) throw new IOException("Photon shader-pack directory has no parent.");

        ShaderPackScanResult scan = new ShaderPackScanner().scan(parent);
        ShaderPackDescriptor descriptor = scan.packs().stream()
                .filter(pack -> pack.source().toAbsolutePath().normalize().equals(packPath))
                .findFirst()
                .orElseThrow(() -> new IOException("CAUSTIQ's scanner rejected Photon: " + scan.issues()));

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            Set<String> defines = new LinkedHashSet<>();
            for (ShaderPackMetadata.Option option : model.options().options()) {
                defines.add(option.name() + "=" + option.defaultValue());
            }
            defines.addAll(Set.of(
                    "MC_VERSION=12111",
                    "MC_GL_VERSION=460",
                    "IS_IRIS=1",
                    "WORLD_OVERWORLD=1",
                    "OVERWORLD=1"
            ));

            List<IrisShaderPackModel.ProgramSet> programSets = new ArrayList<>();
            programSets.add(model.base());
            model.dimensionOverrides().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .forEach(programSets::add);

            IrisGlProgramCompiler compiler = new IrisGlProgramCompiler();
            List<String> failures = new ArrayList<>();
            int compiled = 0;
            for (IrisShaderPackModel.ProgramSet set : programSets) {
                for (IrisShaderPackModel.Program sourceProgram : set.programs().values()) {
                    ShaderPackProgramCatalog.Group group =
                            ShaderPackProgramCatalog.Group.classify(sourceProgram.name());
                    ShaderPackProgramCatalog.Program program = new ShaderPackProgramCatalog.Program(
                            sourceProgram.name(), group, 0, sourceProgram.stages());
                    if (!program.isExecutableShape()) {
                        failures.add(set.directory() + "/" + program.name()
                                + ": incomplete or unsupported shader-stage set " + program.stages().keySet());
                        continue;
                    }
                    try (IrisGlProgram ignored = compiler.compile(program, sources::read, defines)) {
                        compiled++;
                        CaustiqClient.LOGGER.info("Photon CI linked {}/{} program '{}'.",
                                set.directory().isBlank() ? "base" : set.directory(), compiled, program.name());
                    } catch (IOException | RuntimeException exception) {
                        failures.add(set.directory() + "/" + program.name() + ": " + exception.getMessage());
                    }
                }
            }
            if (compiled == 0) failures.add("No Photon programs were compiled.");
            if (!failures.isEmpty()) {
                throw new IOException("Photon OpenGL compilation failures (" + failures.size() + "): "
                        + String.join(" | ", failures));
            }
            return compiled;
        }
    }
}
