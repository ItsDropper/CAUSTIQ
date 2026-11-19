package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IrisShadowProgramSelectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void selectsShadowProgramsFromMappedDimensionWhenBaseSetIsEmpty() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Photon/shaders"));
        Files.writeString(shaders.resolve("dimension.properties"), """
                dimension.world0=minecraft:overworld
                dimension.world-1=minecraft:the_nether
                dimension.world1=minecraft:the_end
                """);
        writeShadowProgram(shaders.resolve("world0"), "shadow_solid");
        writeShadowProgram(shaders.resolve("world0"), "shadow_cutout");
        writeShadowProgram(shaders.resolve("world0"), "shadow_water");
        writeShadowProgram(shaders.resolve("world-1"), "shadow_solid");
        writeShadowProgram(shaders.resolve("world1"), "shadow_cutout");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("photon", "Photon",
                temporaryDirectory.resolve("Photon"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 4, 4, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);

            assertTrue(model.base().isEmpty());
            assertEquals("shadow_solid", IrisShadowProgramSelector.select(
                    model, "minecraft:overworld", "shadow_solid", "shadow"));
            assertEquals("shadow_water", IrisShadowProgramSelector.select(
                    model, "minecraft:overworld", "shadow_water", "shadow_solid"));
            assertEquals("shadow_solid", IrisShadowProgramSelector.select(
                    model, "minecraft:the_nether", "shadow_solid", "shadow_cutout"));
            assertEquals("shadow_cutout", IrisShadowProgramSelector.select(
                    model, "minecraft:the_end", "shadow_cutout", "shadow_solid"));
        }
    }

    @Test
    void doesNotBorrowShadowProgramsFromAnUnrelatedDimension() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Isolated/shaders/world0"));
        writeShadowProgram(shaders, "shadow_solid");
        Files.writeString(shaders.getParent().resolve("dimension.properties"),
                "dimension.world0=minecraft:overworld\\n");

        ShaderPackDescriptor descriptor = new ShaderPackDescriptor("isolated", "Isolated",
                temporaryDirectory.resolve("Isolated"), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, 3, 3, 0);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            assertNull(IrisShadowProgramSelector.select(
                    model, "minecraft:the_nether", "shadow_solid", "shadow"));
        }
    }

    private static void writeShadowProgram(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".vsh"),
                "#version 150\\nvoid main(){ gl_Position=vec4(0.0); }");
        Files.writeString(directory.resolve(name + ".fsh"),
                "#version 150\\nout vec4 color; void main(){ color=vec4(1.0); }");
    }
}
