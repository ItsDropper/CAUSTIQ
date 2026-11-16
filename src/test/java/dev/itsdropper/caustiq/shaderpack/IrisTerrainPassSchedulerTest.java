package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IrisTerrainPassSchedulerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void ordersIrisPassGroupsAndTracksColortexDependencies() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("Pack/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                colortex0Format=RGBA16F
                colortex0Clear=false
                colortex0Mipmap=true
                colortex1Format=RGB10_A2
                colortex1Scale=0.5
                program.composite2.enabled=true
                program.composite3.enabled=false
                """);
        writeProgram(shaders, "gbuffers_terrain",
                "out vec4 albedo; void main(){ albedo=vec4(1.0); }");
        writeProgram(shaders, "composite2",
                "uniform sampler2D colortex0; out vec4 result; void main(){ result=texture(colortex0, vec2(0.5)); }");
        writeProgram(shaders, "composite3",
                "out vec4 result; void main(){ result=vec4(0.0); }");
        writeProgram(shaders, "final",
                "uniform sampler2D colortex0; out vec4 result; void main(){ result=texture(colortex0, vec2(0.5)); }");

        ShaderPackDescriptor descriptor = descriptor("Pack", 8);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor)) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);

            assertEquals(java.util.List.of("gbuffers_terrain", "composite2", "final"),
                    plan.orderedProgramNames());
            assertEquals("RGBA16F", plan.targets().get(0).format());
            assertFalse(plan.targets().get(0).clear());
            assertTrue(plan.targets().get(0).mipmap());
            assertEquals(0.5f, plan.targets().get(1).scaleX());
            assertTrue(plan.passes().get(1).dependencies().contains("gbuffers_terrain"));
            assertTrue(plan.passes().get(2).dependencies().contains("composite2"));
        }
    }

    @Test
    void excludesColorwheelFlywheelEntryPointsFromIrisPassSchedule() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ColorwheelPack/shaders"));
        writeProgram(shaders, "gbuffers_terrain",
                "out vec4 color; void main(){ color=vec4(1.0); }");
        writeProgram(shaders, "clrwl_gbuffers",
                "out vec4 color; void main(){ color=vec4(0.5); }");
        writeProgram(shaders, "clrwl_shadow",
                "out vec4 color; void main(){ color=vec4(0.0); }");

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ColorwheelPack", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);

            assertEquals(java.util.List.of("gbuffers_terrain"), plan.orderedProgramNames());
        }
    }

    @Test
    void dimensionOverrideIsASeparateProgramSetRatherThanMergedWithBase() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("DimensionPack/shaders"));
        Files.writeString(shaders.resolve("dimension.properties"), "dimension.world0=minecraft:overworld\n");
        writeProgram(shaders, "gbuffers_terrain",
                "out vec4 result; void main(){ result=vec4(1.0); }");
        Path world = Files.createDirectories(shaders.resolve("world0"));
        writeProgram(world, "final",
                "out vec4 result; void main(){ result=vec4(0.5); }");

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("DimensionPack", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler()
                    .schedule(model, "minecraft:overworld");
            assertEquals("world0", plan.dimensionDirectory());
            assertEquals(java.util.List.of("final"), plan.orderedProgramNames());
        }
    }

    @Test
    void mapsFragmentOutputSlotsThroughRendertargetsAndDrawbuffersDirectives() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("OutputMappingPack/shaders"));
        writeProgram(shaders, "composite", """
                /* RENDERTARGETS: 0,3,7 */
                layout(location = 0) out vec4 scene;
                layout(location = 1) flat out vec4 normal;
                out vec4 outColor2;
                void main() {
                    scene = vec4(1.0);
                    normal = vec4(0.0, 1.0, 0.0, 1.0);
                    outColor2 = vec4(0.5);
                }
                """);
        writeProgram(shaders, "composite1", """
                /* DRAWBUFFERS: 35 */
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.5);
                }
                """);

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("OutputMappingPack", 4))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);

            assertEquals(java.util.Set.of(0, 3, 7), plan.passes().get(0).pass().writes());
            assertEquals(java.util.Map.of(0, 0, 1, 3, 2, 7),
                    plan.passes().get(0).pass().outputTargets());
            assertEquals(java.util.Set.of(3, 5), plan.passes().get(1).pass().writes());
            assertEquals(java.util.Map.of(0, 3, 1, 5),
                    plan.passes().get(1).pass().outputTargets());
        }
    }


    @Test
    void prefersIrisRenderTargetsWhenLegacyOptifineDrawbuffersAreAlsoPresent() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("DualOutputMapping/shaders"));
        writeProgram(shaders, "composite", """
                /* DRAWBUFFERS: 35 */
                /* RENDERTARGETS: 2,6 */
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(0.5);
                }
                """);

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("DualOutputMapping", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);

            assertEquals(java.util.Map.of(0, 2, 1, 6),
                    plan.passes().getFirst().pass().outputTargets());
        }
    }

    @Test
    void ignoresFragmentOutputsInInactiveConditionalBranches() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ConditionalOutputs/shaders"));
        writeProgram(shaders, "composite", """
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 scene;
                #ifdef OPTIONAL_SECOND_OUTPUT
                layout(location = 1) out vec4 auxiliary;
                #endif
                void main() {
                    scene = vec4(1.0);
                    #ifdef OPTIONAL_SECOND_OUTPUT
                    auxiliary = vec4(0.5);
                    #endif
                }
                """);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ConditionalOutputs", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler scheduler = new IrisTerrainPassScheduler();
            IrisTerrainPassScheduler.Plan inactive = scheduler.schedule(model, null, java.util.Map.of());
            assertEquals(java.util.Map.of(0, 0), inactive.passes().getFirst().pass().outputTargets());
            assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(
                    model, null, java.util.Map.of("OPTIONAL_SECOND_OUTPUT", "1")));
        }
    }

    @Test
    void usesLastActiveRendertargetsDirectiveAndOnlyAssignedOutputs() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ConditionalMrt/shaders"));
        writeProgram(shaders, "composite", """
                /* RENDERTARGETS: 1 */
                layout(location = 0) out vec4 first;
                layout(location = 1) out vec4 second;
                #ifdef EXTRA_OUTPUT
                /* RENDERTARGETS: 1,2 */
                #endif
                void main() {
                    first = vec4(1.0);
                    #ifdef EXTRA_OUTPUT
                    second.xy = vec2(0.5);
                    #endif
                }
                """);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ConditionalMrt", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler scheduler = new IrisTerrainPassScheduler();
            var basic = scheduler.schedule(model, null, java.util.Map.of());
            assertEquals(java.util.Map.of(0, 1), basic.passes().getFirst().pass().outputTargets());
            var extended = scheduler.schedule(model, null, java.util.Map.of("EXTRA_OUTPUT", "1"));
            assertEquals(java.util.Map.of(0, 1, 1, 2),
                    extended.passes().getFirst().pass().outputTargets());
        }
    }

    @Test
    void distinguishesScaledFullscreenTargetsFromScaledTerrainAttachments() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ScaledPack/shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "colortex1Scale=0.5\n");
        writeProgram(shaders, "gbuffers_terrain", """
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main(){ color=vec4(1.0); }
                """);
        writeProgram(shaders, "composite", """
                /* RENDERTARGETS: 1 */
                layout(location = 0) out vec4 color;
                void main(){ color=vec4(0.5); }
                """);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ScaledPack", 5))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);
            assertFalse(plan.hasScaledTerrainWrites());
            assertEquals(0.5f, plan.targets().get(1).scaleX());
        }

        Path terrainShaders = Files.createDirectories(temporaryDirectory.resolve("ScaledTerrainPack/shaders"));
        Files.writeString(terrainShaders.resolve("shaders.properties"), "colortex1Scale=0.5\n");
        writeProgram(terrainShaders, "gbuffers_terrain", """
                /* RENDERTARGETS: 1 */
                layout(location = 0) out vec4 color;
                void main(){ color=vec4(1.0); }
                """);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ScaledTerrainPack", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);
            assertTrue(plan.hasScaledTerrainWrites());
        }
    }

    @Test
    void schedulesFullscreenTargetsBeyondNvidiumsSevenAuxiliaryAttachments() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ExtendedTargets/shaders"));
        writeProgram(shaders, "composite", """
                /* RENDERTARGETS: 8, 12, 15 */
                layout(location = 0) out vec4 first;
                layout(location = 1) out vec4 second;
                layout(location = 2) out vec4 third;
                void main(){ first=vec4(1.0); second=vec4(0.5); third=vec4(0.25); }
                """);
        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ExtendedTargets", 3))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler.Plan plan = new IrisTerrainPassScheduler().schedule(model, null);
            assertEquals(java.util.Set.of(8, 12, 15), plan.passes().getFirst().pass().writes());
            assertTrue(plan.targets().keySet().containsAll(java.util.Set.of(8, 12, 15)));
        }
    }

    @Test
    void schedulesConditionalDimensionScopedComputePassesInDependencyOrder() throws IOException {
        Path shaders = Files.createDirectories(temporaryDirectory.resolve("ComputePack/shaders"));
        Files.writeString(shaders.resolve("dimension.properties"),
                "dimension.world0=minecraft:overworld\\n");
        Files.writeString(shaders.resolve("shaders.properties"), """
                program.world0/setup.enabled=COLORED_LIGHTS
                program.world0/shadowcomp.enabled=COLORED_LIGHTS
                """);
        Path world = Files.createDirectories(shaders.resolve("world0"));
        Files.writeString(world.resolve("setup.csh"), """
                #version 430
                layout(local_size_x = 1) in;
                void main() { }
                """);
        Files.writeString(world.resolve("shadowcomp.csh"), """
                #version 430
                layout(local_size_x = 32) in;
                void main() { }
                """);

        try (ShaderPackSources sources = ShaderPackSources.open(descriptor("ComputePack", 4))) {
            IrisShaderPackModel model = new IrisShaderPackParser().parse(sources);
            IrisTerrainPassScheduler scheduler = new IrisTerrainPassScheduler();
            IrisTerrainPassScheduler.Plan enabled = scheduler.schedule(
                    model, "minecraft:overworld", java.util.Map.of("COLORED_LIGHTS", "1"));
            assertEquals(java.util.List.of("setup", "shadowcomp"), enabled.orderedProgramNames());
            assertTrue(enabled.passes().stream().allMatch(pass -> pass.pass().compute()));
            assertEquals(ShaderPackProgramCatalog.Group.SETUP, enabled.passes().getFirst().pass().group());
            assertEquals(ShaderPackProgramCatalog.Group.SHADOW_COMPOSITE,
                    enabled.passes().getLast().pass().group());

            IrisTerrainPassScheduler.Plan disabled = scheduler.schedule(
                    model, "minecraft:overworld", java.util.Map.of("COLORED_LIGHTS", "false"));
            assertTrue(disabled.passes().isEmpty());
        }
    }

    private void writeProgram(Path directory, String name, String fragment) throws IOException {
        Files.writeString(directory.resolve(name + ".vsh"), """
                #version 150
                void main(){ gl_Position=vec4(0.0); }
                """);
        Files.writeString(directory.resolve(name + ".fsh"), "#version 150\n" + fragment);
    }

    private ShaderPackDescriptor descriptor(String name, int fileCount) {
        return new ShaderPackDescriptor(name.toLowerCase(), name,
                temporaryDirectory.resolve(name), "", ShaderPackSourceType.DIRECTORY,
                ShaderPackLayout.OPTIFINE_STYLE, fileCount, fileCount, 0);
    }

    @Test
    void sharesLegacyDrawBuffersParsingWithTheNvidiumTerrainTranslator() {
        assertEquals(java.util.List.of(3, 5),
                IrisTerrainPassScheduler.parseOutputTargetMapping("/* DRAWBUFFERS: 35 */"));
        assertEquals(java.util.List.of(1, 4),
                IrisTerrainPassScheduler.parseOutputTargetMapping(
                        "/* DRAWBUFFERS: 35 */\\n/* RENDERTARGETS: 1,4 */"));
    }
}
