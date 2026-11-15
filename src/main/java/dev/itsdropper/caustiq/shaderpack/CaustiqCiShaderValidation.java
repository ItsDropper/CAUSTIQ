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


    private static boolean evaluateEnabled(String expression, Map<String, String> defines) {
        String value = expression.trim();
        while (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        int split = topLevelOperator(value, "||");
        if (split >= 0) return evaluateEnabled(value.substring(0, split), defines)
                || evaluateEnabled(value.substring(split + 2), defines);
        split = topLevelOperator(value, "&&");
        if (split >= 0) return evaluateEnabled(value.substring(0, split), defines)
                && evaluateEnabled(value.substring(split + 2), defines);
        if (value.startsWith("!")) return !evaluateEnabled(value.substring(1), defines);
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.isBlank()) return false;
        String option = defines.get(value);
        return option != null && !option.equalsIgnoreCase("false") && !option.equals("0");
    }

    private static int topLevelOperator(String expression, String operator) {
        int depth = 0;
        for (int index = 0; index <= expression.length() - operator.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') depth++;
            else if (current == ')') depth--;
            if (depth == 0 && expression.startsWith(operator, index)) return index;
        }
        return -1;
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
            Map<String, String> optionValues = new java.util.LinkedHashMap<>();
            for (ShaderPackMetadata.Option option : model.options().options()) {
                optionValues.put(option.name(), option.defaultValue());
            }
            // Validate a realistic high-quality Photon profile rather than bare
            // option defaults. Shader-pack profiles enable/disable feature macros
            // that also control whether matching uniforms and samplers are declared.
            String highProfile = model.properties().getOrDefault("profile.high", "");
            for (String token : highProfile.trim().split("\\s+")) {
                if (token.isBlank() || token.equals("<empty>")) continue;
                if (token.startsWith("!")) {
                    optionValues.put(token.substring(1), "false");
                } else {
                    int separator = token.indexOf('=');
                    if (separator < 0) optionValues.put(token, "true");
                    else optionValues.put(token.substring(0, separator), token.substring(separator + 1));
                }
            }
            optionValues.put("SHADOW", "true");
            optionValues.put("MC_VERSION", "12111");
            optionValues.put("MC_GL_VERSION", "450");
            optionValues.put("IS_IRIS", "1");
            optionValues.put("MC_HAND_DEPTH", "1.0");
            optionValues.put("MC_RENDER_QUALITY", "1.0");
            optionValues.put("MC_SHADOW_QUALITY", "1.0");

            List<IrisShaderPackModel.ProgramSet> programSets = new ArrayList<>();
            programSets.add(model.base());
            model.dimensionOverrides().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .forEach(programSets::add);

            IrisGlProgramCompiler compiler = new IrisGlProgramCompiler();
            List<String> failures = new ArrayList<>();
            int compiled = 0;
            Set<ShaderPackProgramCatalog.Group> fullscreenGroups = Set.of(
                    ShaderPackProgramCatalog.Group.SETUP,
                    ShaderPackProgramCatalog.Group.BEGIN,
                    ShaderPackProgramCatalog.Group.PREPARE,
                    ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE,
                    ShaderPackProgramCatalog.Group.DEFERRED,
                    ShaderPackProgramCatalog.Group.COMPOSITE,
                    ShaderPackProgramCatalog.Group.FINAL);
            for (IrisShaderPackModel.ProgramSet set : programSets) {
                Map<String, String> dimensionValues = new java.util.LinkedHashMap<>(optionValues);
                dimensionValues.put("WORLD_OVERWORLD", set.directory().equals("world-1") || set.directory().equals("world1") ? "0" : "1");
                dimensionValues.put("OVERWORLD", dimensionValues.get("WORLD_OVERWORLD"));
                dimensionValues.put("WORLD_NETHER", set.directory().equals("world-1") ? "1" : "0");
                dimensionValues.put("NETHER", dimensionValues.get("WORLD_NETHER"));
                dimensionValues.put("WORLD_END", set.directory().equals("world1") ? "1" : "0");
                dimensionValues.put("END", dimensionValues.get("WORLD_END"));
                if (set.directory().equals("world-1")) {
                    // Photon deliberately omits shadow samplers in the Nether.
                    dimensionValues.put("SHADOW", "false");
                    dimensionValues.put("SHADOW_COLOR", "false");
                    dimensionValues.put("SHADOW_SSRT", "false");
                    dimensionValues.put("SHADOW_PCF", "false");
                    dimensionValues.put("SHADOW_VPS", "false");
                }
                Set<String> dimensionDefines = new LinkedHashSet<>();
                dimensionValues.forEach((name, value) -> dimensionDefines.add(name + "=" + value));

                for (IrisShaderPackModel.Program sourceProgram : set.programs().values()) {
                    ShaderPackProgramCatalog.Group group =
                            ShaderPackProgramCatalog.Group.classify(sourceProgram.name());
                    if (!fullscreenGroups.contains(group)) continue;

                    String enabled = model.conditionalPrograms().get(
                            set.directory().isBlank() ? sourceProgram.name()
                                    : set.directory() + "/" + sourceProgram.name());
                    if (enabled == null) enabled = model.conditionalPrograms().get(sourceProgram.name());
                    if (enabled != null && !evaluateEnabled(enabled, dimensionValues)) {
                        CaustiqClient.LOGGER.info("Photon CI skipped disabled program '{}' in {} (condition: {}).",
                                sourceProgram.name(), set.directory(), enabled);
                        continue;
                    }

                    ShaderPackProgramCatalog.Program program = new ShaderPackProgramCatalog.Program(
                            sourceProgram.name(), group, 0, sourceProgram.stages());
                    boolean computeOnly = program.hasCompute() && program.stages().size() == 1;
                    boolean fragmentPass = program.fragmentSource() != null
                            && !program.hasCompute()
                            && !program.stages().containsKey(ShaderPackProgramCatalog.Stage.GEOMETRY)
                            && !program.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_CONTROL)
                            && !program.stages().containsKey(ShaderPackProgramCatalog.Stage.TESSELLATION_EVALUATION);
                    if (!computeOnly && !fragmentPass) {
                        failures.add(set.directory() + "/" + program.name()
                                + ": incomplete or unsupported shader-stage set " + program.stages().keySet());
                        continue;
                    }
                    try (IrisGlProgram ignored = compiler.compile(program, sources::read, dimensionDefines)) {
                        compiled++;
                        CaustiqClient.LOGGER.info("Photon CI linked {}/{} fullscreen/compute program '{}'.",
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
