package dev.itsdropper.caustiq.shaderpack;

import dev.itsdropper.caustiq.CaustiqClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
            String result = "CAUSTIQ_CI_SHADER_VALIDATION_PASS programs=" + compiled;
            CaustiqClient.LOGGER.info(result);
            System.out.println(result);
        } catch (Exception exception) {
            String result = "CAUSTIQ_CI_SHADER_VALIDATION_FAIL " + exception.getMessage();
            CaustiqClient.LOGGER.error(result, exception);
            System.err.println(result);
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
