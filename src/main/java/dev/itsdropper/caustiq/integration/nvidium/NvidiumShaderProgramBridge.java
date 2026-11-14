package dev.itsdropper.caustiq.integration.nvidium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Compile-boundary bridge for Nvidium task/mesh/fragment terrain programs. */
public final class NvidiumShaderProgramBridge {
    private static final Set<String> TERRAIN_STAGES = Set.of("TASK", "MESH", "FRAGMENT");
    private static final AtomicReference<Snapshot> LAST_TERRAIN_PROGRAM = new AtomicReference<>();
    private static volatile UnaryOperator<Map<String, String>> terrainAdapter;

    private NvidiumShaderProgramBridge() { }

    /** Installs a real pack-to-Nvidium mesh-stage adapter. Identity adapters are not compatibility. */
    public static void registerTerrainAdapter(UnaryOperator<Map<String, String>> adapter) {
        terrainAdapter = Objects.requireNonNull(adapter, "adapter");
    }

    public static void unregisterTerrainAdapter() { terrainAdapter = null; }
    public static boolean hasTerrainAdapter() { return terrainAdapter != null; }

    /** Called immediately before Nvidium links a shader program through OpenGL. */
    @SuppressWarnings("unchecked")
    public static void transformBeforeCompile(Map<?, String> shaderSources) {
        Objects.requireNonNull(shaderSources, "shaderSources");
        Map<String, String> namedSources = new LinkedHashMap<>();
        for (Map.Entry<?, String> entry : shaderSources.entrySet()) {
            if (entry.getKey() instanceof Enum<?> stage) {
                namedSources.put(stage.name(), Objects.requireNonNull(entry.getValue(), "shader source"));
            }
        }
        if (!namedSources.keySet().containsAll(TERRAIN_STAGES)) return;

        LAST_TERRAIN_PROGRAM.set(Snapshot.from(namedSources));
        UnaryOperator<Map<String, String>> adapter = terrainAdapter;
        if (adapter == null) return;

        Map<String, String> transformed = Objects.requireNonNull(
                adapter.apply(Map.copyOf(namedSources)), "terrain adapter result");
        if (!transformed.keySet().equals(namedSources.keySet())) {
            throw new IllegalStateException("Nvidium terrain adapter must preserve shader stages. Expected "
                    + namedSources.keySet() + " but received " + transformed.keySet());
        }
        if (transformed.equals(namedSources)) {
            throw new IllegalStateException("Nvidium terrain adapter did not transform any shader stage.");
        }
        for (Map.Entry<?, String> entry : shaderSources.entrySet()) {
            if (entry.getKey() instanceof Enum<?> stage) {
                String replacement = transformed.get(stage.name());
                if (replacement == null || replacement.isBlank()) {
                    throw new IllegalStateException("Nvidium terrain adapter returned an empty " + stage.name() + " shader.");
                }
                ((Map<Object, String>) shaderSources).put(entry.getKey(), replacement);
            }
        }
    }

    static void resetForTests() {
        terrainAdapter = null;
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
