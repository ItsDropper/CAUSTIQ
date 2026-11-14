#!/usr/bin/env python3
"""Verify CAUSTIQ's mixin assumptions against the pinned Nvidium source tree."""
from pathlib import Path
import json
import re
import sys

root = Path(sys.argv[1])
caustiq_root = Path(sys.argv[2])

def source(relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"Missing pinned Nvidium source: {relative}")
    return path.read_text(encoding="utf-8")

shader = source("src/main/java/me/cortex/nvidium/gl/shader/Shader.java")
iris_check = source("src/main/java/me/cortex/nvidium/sodiumCompat/IrisCheck.java")
render_section = source("src/main/java/me/cortex/nvidium/mixin/sodium/MixinRenderSectionManager.java")
mesh = source("src/main/resources/assets/nvidium/shaders/terrain/mesh.glsl")
fragment = source("src/main/resources/assets/nvidium/shaders/terrain/frag.frag")
primary = source("src/main/java/me/cortex/nvidium/renderers/PrimaryTerrainRasterizer.java")

# The mixin shadows this exact private field and injects at this exact method.
assert re.search(r"private\\s+final\\s+Map<\\s*ShaderType\\s*,\\s*String\\s*>\\s+sources", shader), "Shader.Builder.sources signature changed"
assert re.search(r"public\\s+Shader\\s+compile\\s*\\(\\s*\\)", shader), "Shader.Builder.compile() signature changed"
assert "class Builder" in shader, "Shader.Builder nested class disappeared"
assert "NvidiumShaderBuilderMixin" in (caustiq_root / "src/main/resources/caustiq.mixins.json").read_text()
builder_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumShaderBuilderMixin.java").read_text()
assert 'me.cortex.nvidium.gl.shader.Shader$Builder' in builder_mixin
assert '@Shadow @Final private Map<?, String> sources' in builder_mixin
assert 'method = "compile"' in builder_mixin

# checkIrisShouldDisable() returns true when the Nvidium backend is allowed.
assert re.search(r"public\\s+static\\s+boolean\\s+checkIrisShouldDisable\\s*\\(\\s*\\)", iris_check)
assert "return !(IRIS_LOADED && checkIrisShaders());" in iris_check
assert "IrisCheck.checkIrisShouldDisable()" in render_section
assert "Nvidium.IS_ENABLED = (!Nvidium.FORCE_DISABLE) && Nvidium.IS_COMPATIBLE" in render_section
iris_mixin = (caustiq_root / "src/main/java/dev/itsdropper/caustiq/mixin/NvidiumIrisCheckMixin.java").read_text()
assert 'me.cortex.nvidium.sodiumCompat.IrisCheck' in iris_mixin
assert 'method = "checkIrisShouldDisable"' in iris_mixin
assert "callbackInfo.setReturnValue(true)" in iris_mixin

# The active Nvidium terrain pipeline is TASK + MESH + FRAGMENT, with mesh
# outputs consumed by the fragment shader. Keep this check tied to its source.
assert ".addSource(TASK" in primary and ".addSource(MESH" in primary and ".addSource(FRAGMENT" in primary
assert "layout(triangles, max_vertices=64, max_primitives=32) out;" in mesh
assert "layout(location=1) out Interpolants" in mesh
assert "layout(location = 1) in Interpolants" in fragment
assert "gl_PrimitiveID" in fragment

print("Nvidium source contract verified: Shader.Builder field/method, Iris gate semantics, active three-stage terrain pipeline, and mesh-to-fragment interface.")
