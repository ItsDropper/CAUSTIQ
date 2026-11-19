package dev.itsdropper.caustiq.shaderpack;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Loads bounded, pack-defined options without extracting ZIP archives. */
public final class ShaderPackMetadataLoader {
    private ShaderPackMetadataLoader() { }

    public static ShaderPackMetadata load(ShaderPackDescriptor pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        try (ShaderPackSources sources = ShaderPackSources.open(pack)) {
            List<String> files = sources.listShaderFiles();
            String properties = files.contains("shaders.properties")
                    ? sources.read("shaders.properties") : "";
            return ShaderPackMetadata.parse(properties, sources, files);
        }
    }
}
