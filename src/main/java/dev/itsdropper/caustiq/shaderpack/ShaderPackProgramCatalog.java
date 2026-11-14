package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Discovers the complete conventional Iris/OptiFine shader-program inventory.
 * This is an inventory/planning layer; it does not imply that every stage is
 * executable by the current renderer.
 */
public final class ShaderPackProgramCatalog {
    public static final int MAX_PROGRAMS = 512;

    public enum Stage {
        VERTEX(".vsh"),
        FRAGMENT(".fsh"),
        GEOMETRY(".gsh"),
        COMPUTE(".csh"),
        TESSELLATION_CONTROL(".tcs"),
        TESSELLATION_EVALUATION(".tes");

        private final String extension;
        Stage(String extension) { this.extension = extension; }
        public String extension() { return extension; }

        static Stage fromPath(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (Stage stage : values()) if (lower.endsWith(stage.extension)) return stage;
            return null;
        }
    }

    public enum Group {
        SETUP("setup", 0),
        BEGIN("begin", 1),
        SHADOW("shadow", 2),
        SHADOW_COMPOSITE("shadowcomp", 3),
        PREPARE("prepare", 4),
        GBUFFERS("gbuffers", 5),
        DEFERRED("deferred", 6),
        COMPOSITE("composite", 7),
        FINAL("final", 8),
        UNKNOWN("", Integer.MAX_VALUE);

        private final String prefix;
        private final int order;
        Group(String prefix, int order) { this.prefix = prefix; this.order = order; }
        public int order() { return order; }

        static Group classify(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (matches(lower, "shadowcomp")) return SHADOW_COMPOSITE;
            for (Group group : values()) {
                if (group == UNKNOWN || group == SHADOW_COMPOSITE) continue;
                if (matches(lower, group.prefix)) return group;
            }
            return UNKNOWN;
        }

        private static boolean matches(String name, String prefix) {
            if (name.equals(prefix)) return true;
            if (prefix.equals("gbuffers")) return name.startsWith("gbuffers_");
            if (prefix.equals("shadow")) return name.startsWith("shadow_");
            if (prefix.equals("deferred") || prefix.equals("composite")
                    || prefix.equals("setup") || prefix.equals("begin")
                    || prefix.equals("prepare") || prefix.equals("shadowcomp")) {
                String suffix = name.startsWith(prefix) ? name.substring(prefix.length()) : "";
                return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
            }
            return false;
        }
    }

    public record Program(String name, Group group, int ordinal, Map<Stage, String> stages) {
        public Program {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(group, "group");
            stages = Map.copyOf(stages);
            if (stages.isEmpty()) throw new IllegalArgumentException("A program must contain at least one stage.");
        }
        public String vertexSource() { return stages.get(Stage.VERTEX); }
        public String fragmentSource() { return stages.get(Stage.FRAGMENT); }
        public boolean hasCompute() { return stages.containsKey(Stage.COMPUTE); }
        public boolean isExecutableShape() {
            return hasCompute() || (stages.containsKey(Stage.VERTEX) && stages.containsKey(Stage.FRAGMENT));
        }
    }

    public record Result(List<Program> programs, List<String> ignoredStages, List<String> invalidPrograms) {
        public Result {
            programs = List.copyOf(programs);
            ignoredStages = List.copyOf(ignoredStages);
            invalidPrograms = List.copyOf(invalidPrograms);
        }
        public List<Program> inGroup(Group group) {
            return programs.stream().filter(program -> program.group() == group).toList();
        }
        public boolean requiresWorldPipeline() {
            return programs.stream().anyMatch(program -> program.group() != Group.FINAL);
        }
        public boolean isFinalOnly() {
            return programs.size() == 1 && programs.getFirst().group() == Group.FINAL
                    && programs.getFirst().name().equals("final")
                    && programs.getFirst().stages().containsKey(Stage.FRAGMENT);
        }
    }

    private static final Comparator<Program> PROGRAM_ORDER =
            Comparator.comparingInt((Program program) -> program.group().order())
                    .thenComparingInt(Program::ordinal)
                    .thenComparing(Program::name);

    public Result discover(ShaderPackSources sources) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Map<String, EnumMap<Stage, String>> inventory = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();

        for (String path : sources.listShaderFiles()) {
            Stage stage = Stage.fromPath(path);
            if (stage == null) continue;
            String filename = path.substring(path.lastIndexOf('/') + 1);
            String name = filename.substring(0, filename.length() - stage.extension().length());
            Group group = Group.classify(name);
            if (group == Group.UNKNOWN) {
                ignored.add(path);
                continue;
            }
            EnumMap<Stage, String> stages = inventory.computeIfAbsent(name, ignoredName -> new EnumMap<>(Stage.class));
            if (stages.putIfAbsent(stage, path) != null) {
                throw new IOException("Shader program '" + name + "' contains duplicate " + stage + " stages.");
            }
        }

        if (inventory.size() > MAX_PROGRAMS) {
            throw new IOException("Shader pack contains more than " + MAX_PROGRAMS + " recognized shader programs.");
        }

        List<Program> programs = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, EnumMap<Stage, String>> entry : inventory.entrySet()) {
            String name = entry.getKey();
            Group group = Group.classify(name);
            int ordinal = numericSuffix(name, group);
            Program program = new Program(name, group, ordinal, entry.getValue());
            if (!program.isExecutableShape()) invalid.add(name);
            programs.add(program);
        }

        programs.sort(PROGRAM_ORDER);
        ignored.sort(String::compareTo);
        invalid.sort(String::compareTo);
        return new Result(programs, ignored, invalid);
    }

    private static int numericSuffix(String name, Group group) {
        if (group == Group.GBUFFERS || group == Group.SHADOW || group == Group.FINAL) return 0;
        String suffix = name.substring(group.prefix.length());
        if (suffix.isEmpty()) return 0;
        try { return Integer.parseInt(suffix); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }
}
