package dev.itsdropper.caustiq.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an explicit, deterministic execution schedule from Iris' program-set
 * and colortex declarations. This is the renderer-facing contract consumed by
 * the pass executor; it never merges dimension overrides with the base set.
 */
public final class IrisTerrainPassScheduler {
    private static final Pattern NUMBERED_PROGRAM = Pattern.compile("^(.*?)(\\d+)$");
    private static final Pattern TARGET_REFERENCE = Pattern.compile("\\bcolortex(\\d+)\\b");
    private static final Pattern OUTPUT_LOCATION = Pattern.compile(
            "(?m)layout\\s*\\([^)]*location\\s*=\\s*(\\d+)[^)]*\\)\\s*" +
                    "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)\\s+)*out\\s+");
    private static final Pattern OUTPUT_NAME = Pattern.compile("\\boutColor(\\d+)\\b");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
    private static final Pattern RENDERTARGETS = Pattern.compile(
            "/\\*\\s*RENDERTARGETS\\s*:\\s*([^*]*?)\\*/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRAWBUFFERS = Pattern.compile(
            "/\\*\\s*DRAWBUFFERS\\s*:\\s*([^*]*?)\\*/", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_KEY = Pattern.compile(
            "^([0-9]+)(Format|Clear|ClearColor|Mipmap|Persistent|Scale|ScaleX|ScaleY|Nearest)$",
            Pattern.CASE_INSENSITIVE);

    public Plan schedule(IrisShaderPackModel model, String dimensionKey) {
        Objects.requireNonNull(model, "model");
        IrisShaderPackModel.ProgramSet programSet = selectProgramSet(model, dimensionKey);
        Map<Integer, RenderTarget> targets = parseTargets(model.renderTargetDirectives());
        List<Pass> passes = new ArrayList<>();

        for (IrisShaderPackModel.Program program : programSet.programs().values()) {
            ShaderPackProgramCatalog.Group group = ShaderPackProgramCatalog.Group.classify(program.name());
            if (group == ShaderPackProgramCatalog.Group.UNKNOWN) {
                throw new IllegalArgumentException("Unknown Iris program group: " + program.name());
            }
            String enabled = model.conditionalPrograms().get(program.name());
            if (enabled != null && isLiteralFalse(enabled)) continue;

            String vertex = source(model, program, ShaderPackProgramCatalog.Stage.VERTEX);
            String fragment = source(model, program, ShaderPackProgramCatalog.Stage.FRAGMENT);
            String compute = source(model, program, ShaderPackProgramCatalog.Stage.COMPUTE);
            Set<Integer> reads = new LinkedHashSet<>();
            Set<Integer> writes = new LinkedHashSet<>();
            collectTargets(vertex, reads);
            collectTargets(fragment, reads);
            collectTargets(compute, reads);
            Map<Integer, Integer> outputTargets = collectOutputs(fragment);
            writes.addAll(outputTargets.values());
            if (program.hasCompute()) writes.addAll(parseImageBindings(compute));

            for (int target : writes) {
                if (!targets.containsKey(target)) {
                    targets.put(target, RenderTarget.defaultTarget(target));
                }
            }
            passes.add(new Pass(program.name(), group, program.stages(), reads, writes, outputTargets,
                    program.hasCompute(), program.hasGeometryOrTessellation()));
        }

        passes.sort(Comparator.comparingInt((Pass pass) -> pass.group().order())
                .thenComparingInt(pass -> numericSuffix(pass.name()))
                .thenComparing(Pass::name, String.CASE_INSENSITIVE_ORDER));

        Map<Integer, String> lastWriter = new LinkedHashMap<>();
        List<ScheduledPass> scheduled = new ArrayList<>(passes.size());
        for (int index = 0; index < passes.size(); index++) {
            Pass pass = passes.get(index);
            Set<String> dependencies = new LinkedHashSet<>();
            for (int target : pass.reads()) {
                String writer = lastWriter.get(target);
                if (writer != null && !writer.equals(pass.name())) dependencies.add(writer);
            }
            scheduled.add(new ScheduledPass(index, pass, dependencies));
            for (int target : pass.writes()) lastWriter.put(target, pass.name());
        }

        return new Plan(programSet.directory(), targets, scheduled);
    }

    private static IrisShaderPackModel.ProgramSet selectProgramSet(
            IrisShaderPackModel model, String dimensionKey) {
        if (dimensionKey != null && !dimensionKey.isBlank()) {
            String directory = model.dimensionMappings().get(dimensionKey);
            if (directory != null) {
                IrisShaderPackModel.ProgramSet override = model.dimensionOverrides().get(directory);
                if (override != null) return override;
            }
        }
        return model.base();
    }

    private static String source(
            IrisShaderPackModel model, IrisShaderPackModel.Program program,
            ShaderPackProgramCatalog.Stage stage) {
        String path = program.stages().get(stage);
        return path == null ? "" : model.preprocessedSources().getOrDefault(path, "");
    }

    private static void collectTargets(String source, Set<Integer> targets) {
        Matcher matcher = TARGET_REFERENCE.matcher(source);
        while (matcher.find()) targets.add(Integer.parseInt(matcher.group(1)));
    }

    /**
     * Resolves GLSL fragment output slots to Iris colortex indices. RENDERTARGETS
     * and legacy DRAWBUFFERS comments describe the mapping; layout(location) and
     * gl_FragData indices are only output slots, not colortex indices themselves.
     */
    private static Map<Integer, Integer> collectOutputs(String source) {
        Set<Integer> outputSlots = new LinkedHashSet<>();
        Matcher locations = OUTPUT_LOCATION.matcher(source);
        while (locations.find()) outputSlots.add(Integer.parseInt(locations.group(1)));
        Matcher named = OUTPUT_NAME.matcher(source);
        while (named.find()) outputSlots.add(Integer.parseInt(named.group(1)));
        Matcher legacy = FRAG_DATA.matcher(source);
        while (legacy.find()) outputSlots.add(Integer.parseInt(legacy.group(1)));
        if (outputSlots.isEmpty()) {
            return source.isBlank() ? Map.of() : Map.of(0, 0);
        }

        List<Integer> targetMapping = parseOutputTargetMapping(source);
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int outputSlot : outputSlots) {
            int target;
            if (targetMapping == null) {
                target = outputSlot;
            } else {
                if (outputSlot >= targetMapping.size()) {
                    throw new IllegalArgumentException("Fragment output slot " + outputSlot
                            + " has no entry in its Iris RENDERTARGETS/DRAWBUFFERS directive.");
                }
                target = targetMapping.get(outputSlot);
            }
            result.put(outputSlot, target);
        }
        return Map.copyOf(result);
    }

    private static List<Integer> parseOutputTargetMapping(String source) {
        Matcher renderTargets = RENDERTARGETS.matcher(source);
        Matcher drawBuffers = DRAWBUFFERS.matcher(source);
        boolean hasRenderTargets = renderTargets.find();
        boolean hasDrawBuffers = drawBuffers.find();
        if (hasRenderTargets && hasDrawBuffers) {
            throw new IllegalArgumentException("A fragment shader cannot declare both RENDERTARGETS and DRAWBUFFERS.");
        }
        if (hasRenderTargets) {
            List<Integer> result = new ArrayList<>();
            for (String token : renderTargets.group(1).trim().split("\\s*,\\s*")) {
                if (!token.matches("\\d+")) {
                    throw new IllegalArgumentException("Invalid Iris RENDERTARGETS entry: " + token);
                }
                result.add(Integer.parseInt(token));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Iris RENDERTARGETS directive is empty.");
            return List.copyOf(result);
        }
        if (hasDrawBuffers) {
            String digits = drawBuffers.group(1).replaceAll("\\s+", "");
            if (digits.isEmpty() || !digits.matches("[0-7]+")) {
                throw new IllegalArgumentException("Invalid Iris DRAWBUFFERS directive: " + drawBuffers.group(1));
            }
            List<Integer> result = new ArrayList<>(digits.length());
            for (int i = 0; i < digits.length(); i++) result.add(digits.charAt(i) - '0');
            return List.copyOf(result);
        }
        return null;
    }

    private static Set<Integer> parseImageBindings(String source) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "layout\\s*\\([^)]*binding\\s*=\\s*(\\d+)[^)]*\\)\\s*uniform\\s+image")
                .matcher(source);
        while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        return result;
    }

    private static Map<Integer, RenderTarget> parseTargets(Map<String, String> directives) {
        Map<Integer, MutableTarget> mutable = new LinkedHashMap<>();
        directives.forEach((key, value) -> {
            Matcher matcher = TARGET_KEY.matcher(key);
            if (!matcher.matches()) return;
            int index = Integer.parseInt(matcher.group(1));
            MutableTarget target = mutable.computeIfAbsent(index, MutableTarget::new);
            switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "format" -> target.format = value;
                case "clear" -> target.clear = Boolean.parseBoolean(value);
                case "clearcolor" -> target.clearColor = value;
                case "mipmap" -> target.mipmap = Boolean.parseBoolean(value);
                case "persistent" -> target.persistent = Boolean.parseBoolean(value);
                case "scale" -> target.scale = parseScale(value, key);
                case "scalex" -> target.scaleX = parseScale(value, key);
                case "scaley" -> target.scaleY = parseScale(value, key);
                case "nearest" -> target.nearest = Boolean.parseBoolean(value);
                default -> throw new IllegalStateException("Unhandled colortex directive " + key);
            }
        });
        Map<Integer, RenderTarget> result = new LinkedHashMap<>();
        mutable.forEach((index, target) -> result.put(index, target.freeze()));
        return result;
    }

    private static float parseScale(String value, String key) {
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed) || parsed <= 0.0f || parsed > 8.0f) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Iris render-target scale " + key + "=" + value, exception);
        }
    }

    private static boolean isLiteralFalse(String expression) {
        return expression.trim().equalsIgnoreCase("false")
                || expression.trim().equals("0");
    }

    private static int numericSuffix(String name) {
        Matcher matcher = NUMBERED_PROGRAM.matcher(name);
        if (!matcher.matches()) return 0;
        try { return Integer.parseInt(matcher.group(2)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static final class MutableTarget {
        private final int index;
        private String format = "RGBA8";
        private boolean clear = true;
        private String clearColor = "0.0 0.0 0.0 0.0";
        private boolean mipmap;
        private boolean persistent;
        private float scale = 1.0f;
        private float scaleX = 1.0f;
        private float scaleY = 1.0f;
        private boolean nearest;

        private MutableTarget(int index) { this.index = index; }
        private RenderTarget freeze() {
            return new RenderTarget(index, format, clear, clearColor, mipmap, persistent,
                    scale * scaleX, scale * scaleY, nearest);
        }
    }

    public record RenderTarget(
            int index, String format, boolean clear, String clearColor,
            boolean mipmap, boolean persistent, float scaleX, float scaleY, boolean nearest) {
        public RenderTarget {
            if (index < 0 || index > 15) throw new IllegalArgumentException("Iris colortex index out of range: " + index);
            if (format == null || format.isBlank()) format = "RGBA8";
            Objects.requireNonNull(clearColor, "clearColor");
        }
        static RenderTarget defaultTarget(int index) {
            return new RenderTarget(index, "RGBA8", true, "0.0 0.0 0.0 0.0",
                    false, false, 1.0f, 1.0f, false);
        }
    }

    public record Pass(
            String name, ShaderPackProgramCatalog.Group group,
            Map<ShaderPackProgramCatalog.Stage, String> stages,
            Set<Integer> reads, Set<Integer> writes,
            Map<Integer, Integer> outputTargets,
            boolean compute, boolean geometryOrTessellation) {
        public Pass {
            stages = Map.copyOf(stages);
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
            outputTargets = Map.copyOf(outputTargets);
        }

        /** Compatibility constructor for synthetic passes with identity output routing. */
        public Pass(String name, ShaderPackProgramCatalog.Group group,
                    Map<ShaderPackProgramCatalog.Stage, String> stages,
                    Set<Integer> reads, Set<Integer> writes,
                    boolean compute, boolean geometryOrTessellation) {
            this(name, group, stages, reads, writes, identityOutputTargets(writes),
                    compute, geometryOrTessellation);
        }

        private static Map<Integer, Integer> identityOutputTargets(Set<Integer> writes) {
            Map<Integer, Integer> result = new LinkedHashMap<>();
            for (int target : writes) result.put(target, target);
            return Map.copyOf(result);
        }
    }

    public record ScheduledPass(int sequence, Pass pass, Set<String> dependencies) {
        public ScheduledPass { dependencies = Set.copyOf(dependencies); }
    }

    public record Plan(String dimensionDirectory, Map<Integer, RenderTarget> targets,
                       List<ScheduledPass> passes) {
        public Plan {
            targets = Map.copyOf(targets);
            passes = List.copyOf(passes);
        }
        public boolean isEmpty() { return passes.isEmpty(); }

        /** True when Nvidium terrain would have to rasterize directly into a scaled attachment. */
        public boolean hasScaledTerrainWrites() {
            for (ScheduledPass scheduled : passes) {
                if (scheduled.pass().group() != ShaderPackProgramCatalog.Group.GBUFFERS) continue;
                for (int targetIndex : scheduled.pass().writes()) {
                    RenderTarget target = targets.get(targetIndex);
                    if (target != null && (target.scaleX() != 1.0f || target.scaleY() != 1.0f)) return true;
                }
            }
            return false;
        }

        public List<String> orderedProgramNames() {
            return passes.stream().map(scheduled -> scheduled.pass().name()).toList();
        }
    }
}
