package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds an inventory of conventional vertex/fragment shader programs. */
public final class ShaderPackProgramCatalog {
    public static final int MAX_PROGRAMS = 128;
    public record Program(String name, String vertexSource, String fragmentSource) {
        public Program {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(vertexSource, "vertexSource");
            Objects.requireNonNull(fragmentSource, "fragmentSource");
        }
    }
    public record Result(List<Program> programs, List<String> ignoredStages) {
        public Result {
            programs = List.copyOf(programs);
            ignoredStages = List.copyOf(ignoredStages);
        }
    }

    public Result discover(ShaderPackSources sources) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Map<String, String> vertices = new LinkedHashMap<>();
        Map<String, String> fragments = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (String path : sources.listShaderFiles()) {
            if (path.endsWith(".vsh")) vertices.put(path.substring(0, path.length() - 4), path);
            else if (path.endsWith(".fsh")) fragments.put(path.substring(0, path.length() - 4), path);
            else if (path.endsWith(".gsh") || path.endsWith(".csh") || path.endsWith(".tcs") || path.endsWith(".tes")) ignored.add(path);
        }
        List<Program> programs = new ArrayList<>();
        for (Map.Entry<String, String> vertex : vertices.entrySet()) {
            String fragment = fragments.get(vertex.getKey());
            if (fragment == null) ignored.add(vertex.getValue());
            else {
                programs.add(new Program(vertex.getKey(), vertex.getValue(), fragment));
                if (programs.size() > MAX_PROGRAMS) throw new IOException("Shader pack contains more than " + MAX_PROGRAMS + " conventional programs.");
            }
        }
        for (Map.Entry<String, String> fragment : fragments.entrySet()) {
            if (!vertices.containsKey(fragment.getKey())) ignored.add(fragment.getValue());
        }
        programs.sort(Comparator.comparing(Program::name));
        ignored.sort(String::compareTo);
        return new Result(programs, ignored);
    }
}
