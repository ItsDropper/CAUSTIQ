package dev.itsdropper.caustiq.integration.nvidium;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Compile-boundary bridge for Nvidium task/mesh/fragment terrain programs. */
public final class NvidiumShaderProgramBridge {
    private static final Set<String> TERRAIN_STAGES = Set.of("TASK", "MESH", "FRAGMENT");
    private static final AtomicReference<Snapshot> LAST_TERRAIN_PROGRAM = new AtomicReference<>();
    private static volatile UnaryOperator<Map<String, String>> nativeSourceAdapter = NvidiumTerrainVaryingAdapter::adapt;
    private static volatile UnaryOperator<Map<String, String>> primaryTerrainAdapter;
    private static volatile UnaryOperator<Map<String, String>> translucentTerrainAdapter;
    private static volatile List<Integer> primaryOutputRouting = List.of(0, 1, 2, 3, 4, 5, 6, 7);
    private static volatile List<Integer> translucentOutputRouting = List.of(0, 1, 2, 3, 4, 5, 6, 7);
    private static final ThreadLocal<PipelineKind> ACTIVE_PIPELINE =
            ThreadLocal.withInitial(() -> PipelineKind.PRIMARY);

    private NvidiumShaderProgramBridge() { }

    /** Installs CAUSTIQ's Nvidium-version-specific native varying extension. */
    public static void registerNativeSourceAdapter(UnaryOperator<Map<String, String>> adapter) {
        nativeSourceAdapter = Objects.requireNonNull(adapter, "adapter");
    }

    /** True when the pinned Nvidium vertex-format adapter is installed. */
    public static boolean hasNativeSourceAdapter() {
        return nativeSourceAdapter != null;
    }

    /** Installs the primary opaque/cutout terrain translator. */
    public static void registerTerrainAdapter(UnaryOperator<Map<String, String>> adapter) {
        registerTerrainAdapter(adapter, List.of(0, 1, 2, 3, 4, 5, 6, 7));
    }

    public static void registerTerrainAdapter(
            UnaryOperator<Map<String, String>> adapter, List<Integer> outputRouting) {
        primaryTerrainAdapter = Objects.requireNonNull(adapter, "adapter");
        primaryOutputRouting = List.copyOf(outputRouting);
    }

    /** Installs the separate translucent terrain translator. */
    public static void registerTranslucentTerrainAdapter(
            UnaryOperator<Map<String, String>> adapter, List<Integer> outputRouting) {
        translucentTerrainAdapter = Objects.requireNonNull(adapter, "adapter");
        translucentOutputRouting = List.copyOf(outputRouting);
    }

    public static void unregisterTerrainAdapter() {
        primaryTerrainAdapter = null;
        translucentTerrainAdapter = null;
        primaryOutputRouting = List.of(0, 1, 2, 3, 4, 5, 6, 7);
        translucentOutputRouting = List.of(0, 1, 2, 3, 4, 5, 6, 7);
        ACTIVE_PIPELINE.remove();
    }

    public static void unregisterTerrainAdapters() {
        nativeSourceAdapter = null;
        unregisterTerrainAdapter();
    }

    /** True only when an Iris terrain program translator is installed. */
    public static boolean hasTerrainAdapter() {
        return primaryTerrainAdapter != null || translucentTerrainAdapter != null;
    }

    public static List<Integer> outputRoutingFor(PipelineKind pipeline) {
        return pipeline == PipelineKind.TRANSLUCENT ? translucentOutputRouting : primaryOutputRouting;
    }

    public static void beginPipelineCompile(PipelineKind pipeline) {
        ACTIVE_PIPELINE.set(Objects.requireNonNull(pipeline, "pipeline"));
    }

    public static void endPipelineCompile() {
        ACTIVE_PIPELINE.remove();
    }

    public enum PipelineKind {
        PRIMARY,
        TRANSLUCENT
    }

    /** Called immediately before Nvidium links a shader program through OpenGL. */
    @SuppressWarnings("unchecked")
    public static void transformBeforeCompile(Map<?, String> shaderSources) {
        Objects.requireNonNull(shaderSources, "shaderSources");
        Map<String, String> original = new LinkedHashMap<>();
        for (Map.Entry<?, String> entry : shaderSources.entrySet()) {
            if (entry.getKey() instanceof Enum<?> stage) {
                original.put(stage.name(), Objects.requireNonNull(entry.getValue(), "shader source"));
            }
        }
        if (!original.keySet().containsAll(TERRAIN_STAGES)) return;

        LAST_TERRAIN_PROGRAM.set(Snapshot.from(original));
        Map<String, String> transformed = original;
        UnaryOperator<Map<String, String>> nativeAdapter = nativeSourceAdapter;
        if (nativeAdapter != null) transformed = applyAdapter(nativeAdapter, transformed, "native Nvidium", false);
        PipelineKind pipeline = ACTIVE_PIPELINE.get();
        UnaryOperator<Map<String, String>> irisAdapter = pipeline == PipelineKind.TRANSLUCENT
                ? translucentTerrainAdapter : primaryTerrainAdapter;
        if (irisAdapter != null) {
            transformed = applyAdapter(irisAdapter, transformed,
                    pipeline == PipelineKind.TRANSLUCENT ? "Iris translucent terrain" : "Iris terrain", true);
        }

        if (transformed.equals(original)) return;
        for (Map.Entry<?, String> entry : shaderSources.entrySet()) {
            if (entry.getKey() instanceof Enum<?> stage) {
                String replacement = transformed.get(stage.name());
                if (replacement == null || replacement.isBlank()) {
                    throw new IllegalStateException("Terrain adapter returned an empty " + stage.name() + " shader.");
                }
                ((Map<Object, String>) shaderSources).put(entry.getKey(), replacement);
            }
        }
    }

    private static Map<String, String> applyAdapter(
            UnaryOperator<Map<String, String>> adapter, Map<String, String> input, String label, boolean rejectNoOp) {
        Map<String, String> transformed = Objects.requireNonNull(
                adapter.apply(Map.copyOf(input)), label + " adapter result");
        if (!transformed.keySet().equals(input.keySet())) {
            throw new IllegalStateException(label + " adapter must preserve shader stages. Expected "
                    + input.keySet() + " but received " + transformed.keySet());
        }
        if (rejectNoOp && transformed.equals(input) && NvidiumTerrainVaryingAdapter.isPrimary(input)) {
            throw new IllegalStateException(label + " adapter did not transform the primary terrain shader stages.");
        }
        for (Map.Entry<String, String> entry : transformed.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalStateException(label + " adapter returned an empty " + entry.getKey() + " shader.");
            }
        }
        return Map.copyOf(transformed);
    }

    static void resetForTests() {
        nativeSourceAdapter = null;
        unregisterTerrainAdapter();
        LAST_TERRAIN_PROGRAM.set(null);
    }

    public static Snapshot lastTerrainProgram() { return LAST_TERRAIN_PROGRAM.get(); }
    public record StageSnapshot(int sourceLength, int sourceHash) { }
    public record Snapshot(Map<String, StageSnapshot> stages) {
        public Snapshot { stages = Map.copyOf(stages); }
        private static Snapshot from(Map<String, String> sources) {
            Map<String, StageSnapshot> stages = new LinkedHashMap<>();
            sources.forEach((stage, source) -> stages.put(stage, new StageSnapshot(source.length(), source.hashCode())));
            return new Snapshot(stages);
        }
    }
}
