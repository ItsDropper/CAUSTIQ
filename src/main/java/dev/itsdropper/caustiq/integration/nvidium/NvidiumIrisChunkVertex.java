package dev.itsdropper.caustiq.integration.nvidium;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Nvidium's Sodium-compatible 20-byte base vertex plus Iris terrain attributes.
 *
 * Layout: Sodium base (20), packed block/entity ID (4), octahedral normal +
 * tangent angle (4), quad midpoint UV (4), midpoint block offset/emission (4),
 * and Nvidium mip/alpha-cutoff flags (4). The explicit 40-byte stride is also
 * mirrored by the generated Nvidium GLSL Vertex struct.
 */
public final class NvidiumIrisChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 40;
    public static final NvidiumIrisChunkVertex INSTANCE = new NvidiumIrisChunkVertex();
    private static final int POSITION_MAX_VALUE = 1 << 20;
    private static final int TEXTURE_MAX_VALUE = 1 << 15;
    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final GlVertexFormat FORMAT = new GlVertexFormat(null, null, STRIDE);
    private static volatile boolean enabled;

    private NvidiumIrisChunkVertex() { }

    public static void enable() { enabled = true; }
    public static boolean isEnabled() {
        if (!enabled) return false;
        try {
            Class<?> nvidium = Class.forName("me.cortex.nvidium.Nvidium");
            return nvidium.getField("IS_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    @Override public GlVertexFormat getVertexFormat() { return FORMAT; }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, material, vertices, section) -> {
            if (vertices.length != 4) {
                throw new IllegalArgumentException("Nvidium terrain encoder requires quad geometry.");
            }
            NvidiumIrisVertexContext.BlockData context = NvidiumIrisVertexContext.current();
            int midpointUv = midpointUv(vertices);
            Basis basis = basis(vertices);
            int normalTangent = encodeNormalTangent(basis.nx, basis.ny, basis.nz,
                    basis.tx, basis.ty, basis.tz);
            int blockEntity = ((context.blockId() + 1) << 1) | (context.renderType() & 1);
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                int originalAlpha = (vertex.color >>> 24) & 0xFF;
                int nvidiumMaterial = (material & 0xFF) | (originalAlpha << 8);
                int x = quantizePosition(vertex.x);
                int y = quantizePosition(vertex.y);
                int z = quantizePosition(vertex.z);
                int light = compactLight(vertex.light);
                int u = encodeTexture(midpointUvU(vertices), vertex.u);
                int v = encodeTexture(midpointUvV(vertices), vertex.v);
                int midBlock = packMidBlock(
                        context.localX() + 0.5f - vertex.x,
                        context.localY() + 0.5f - vertex.y,
                        context.localZ() + 0.5f - vertex.z,
                        context.emission());

                MemoryUtil.memPutInt(ptr, packPositionHi(x, y, z));
                MemoryUtil.memPutInt(ptr + 4L, packPositionLo(x, y, z));
                MemoryUtil.memPutInt(ptr + 8L, encodeColor(vertex.color, vertex.ao));
                MemoryUtil.memPutInt(ptr + 12L, (u & 0xFFFF) | ((v & 0xFFFF) << 16));
                MemoryUtil.memPutInt(ptr + 16L, (light & 0xFFFF)
                        | ((basis.tangentHandedness > 0.0f ? 1 : 0) << 16)
                        | ((section & 0xFF) << 24));
                MemoryUtil.memPutInt(ptr + 20L, blockEntity);
                MemoryUtil.memPutInt(ptr + 24L, normalTangent);
                MemoryUtil.memPutInt(ptr + 28L, midpointUv);
                MemoryUtil.memPutInt(ptr + 32L, midBlock);
                MemoryUtil.memPutInt(ptr + 36L, nvidiumMaterial);
                ptr += STRIDE;
            }
            return ptr;
        };
    }

    private static int quantizePosition(float value) {
        return ((int) (((MODEL_ORIGIN + value) / MODEL_RANGE) * POSITION_MAX_VALUE)) & 0xFFFFF;
    }

    private static int packPositionHi(int x, int y, int z) {
        return ((x >>> 10) & 0x3FF) | (((y >>> 10) & 0x3FF) << 10)
                | (((z >>> 10) & 0x3FF) << 20);
    }

    private static int packPositionLo(int x, int y, int z) {
        return (x & 0x3FF) | ((y & 0x3FF) << 10) | ((z & 0x3FF) << 20);
    }

    private static int encodeColor(int color, float brightness) {
        return ColorARGB.mulRGB(color, brightness);
    }

    private static int compactLight(int light) {
        int sky = Mth.clamp(((light >>> 16) & 0xFF) + 8, 8, 248);
        int block = Mth.clamp((light & 0xFF) + 8, 8, 248);
        return block | (sky << 8);
    }

    private static int encodeTexture(float center, float coordinate) {
        int bias = coordinate < center ? 1 : -1;
        int quantized = Math.round(coordinate * TEXTURE_MAX_VALUE) + bias;
        return (quantized & 0x7FFF) | ((bias < 0 ? 1 : 0) << 15);
    }

    private static int midpointUv(ChunkVertexEncoder.Vertex[] vertices) {
        return (Math.round(midpointUvU(vertices) * 32768.0f) & 0xFFFF)
                | ((Math.round(midpointUvV(vertices) * 32768.0f) & 0xFFFF) << 16);
    }

    private static float midpointUvU(ChunkVertexEncoder.Vertex[] vertices) {
        return (vertices[0].u + vertices[1].u + vertices[2].u + vertices[3].u) * 0.25f;
    }

    private static float midpointUvV(ChunkVertexEncoder.Vertex[] vertices) {
        return (vertices[0].v + vertices[1].v + vertices[2].v + vertices[3].v) * 0.25f;
    }

    private static int packMidBlock(float x, float y, float z, int emission) {
        return ((int) (x * 64.0f) & 0xFF)
                | (((int) (y * 64.0f) & 0xFF) << 8)
                | (((int) (z * 64.0f) & 0xFF) << 16)
                | ((emission & 0xFF) << 24);
    }

    private static Basis basis(ChunkVertexEncoder.Vertex[] v) {
        float dx0 = v[2].x - v[0].x;
        float dy0 = v[2].y - v[0].y;
        float dz0 = v[2].z - v[0].z;
        float dx1 = v[3].x - v[1].x;
        float dy1 = v[3].y - v[1].y;
        float dz1 = v[3].z - v[1].z;
        float nx = dy0 * dz1 - dz0 * dy1;
        float ny = dz0 * dx1 - dx0 * dz1;
        float nz = dx0 * dy1 - dy0 * dx1;
        float normalLength = length(nx, ny, nz);
        if (normalLength < 1.0e-12f) { nx = 0.0f; ny = 1.0f; nz = 0.0f; }
        else { nx /= normalLength; ny /= normalLength; nz /= normalLength; }

        float[] tangent = tangent(v[0], v[1], v[2]);
        if (tangent == null) tangent = tangent(v[2], v[3], v[0]);
        if (tangent == null) {
            float ax = Math.abs(ny) < 0.9f ? 0.0f : 1.0f;
            float ay = Math.abs(ny) < 0.9f ? 1.0f : 0.0f;
            tangent = normalize(cross(ax, ay, 0.0f, nx, ny, nz));
        }
        float[] bitangent = tangent(v[0], v[1], v[2], true);
        if (bitangent == null) bitangent = new float[]{0.0f, 0.0f, 0.0f};
        float cx = ny * tangent[2] - nz * tangent[1];
        float cy = nz * tangent[0] - nx * tangent[2];
        float cz = nx * tangent[1] - ny * tangent[0];
        float handedness = cx * bitangent[0] + cy * bitangent[1] + cz * bitangent[2] < 0.0f ? -1.0f : 1.0f;
        return new Basis(nx, ny, nz, tangent[0], tangent[1], tangent[2], handedness);
    }

    private static float[] tangent(ChunkVertexEncoder.Vertex a, ChunkVertexEncoder.Vertex b,
                                   ChunkVertexEncoder.Vertex c) {
        return tangent(a, b, c, false);
    }

    private static float[] tangent(ChunkVertexEncoder.Vertex a, ChunkVertexEncoder.Vertex b,
                                   ChunkVertexEncoder.Vertex c, boolean bitangent) {
        float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
        float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
        float du1 = b.u - a.u, dv1 = b.v - a.v;
        float du2 = c.u - a.u, dv2 = c.v - a.v;
        float determinant = du1 * dv2 - du2 * dv1;
        if (Math.abs(determinant) < 1.0e-12f) return null;
        float inverse = 1.0f / determinant;
        float x = bitangent ? (-du2 * e1x + du1 * e2x) * inverse : (dv2 * e1x - dv1 * e2x) * inverse;
        float y = bitangent ? (-du2 * e1y + du1 * e2y) * inverse : (dv2 * e1y - dv1 * e2y) * inverse;
        float z = bitangent ? (-du2 * e1z + du1 * e2z) * inverse : (dv2 * e1z - dv1 * e2z) * inverse;
        float length = length(x, y, z);
        return length < 1.0e-12f ? null : new float[]{x / length, y / length, z / length};
    }

    private static float[] normalize(float[] value) {
        float length = length(value[0], value[1], value[2]);
        if (length < 1.0e-12f) return new float[]{1.0f, 0.0f, 0.0f};
        return new float[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private static float[] cross(float ax, float ay, float az, float bx, float by, float bz) {
        return normalize(new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx});
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static int encodeNormalTangent(float nx, float ny, float nz,
                                           float tx, float ty, float tz) {
        float reciprocal = 1.0f / (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        float px = nx * reciprocal;
        float py = ny * reciprocal;
        if (nz <= 0.0f) {
            float oldX = px;
            px = (1.0f - Math.abs(py)) * signNotZero(oldX);
            py = (1.0f - Math.abs(oldX)) * signNotZero(py);
        }
        int qx = Math.round(clamp(px) * 2047.0f);
        int qy = Math.round(clamp(py) * 2047.0f);
        int encodedNormal = ((qx & 0xFFF) << 12) | (qy & 0xFFF);

        float[] t1 = onbTangent(nx, ny, nz);
        float[] t2 = cross(nx, ny, nz, t1[0], t1[1], t1[2]);
        float dot = nx * tx + ny * ty + nz * tz;
        float tangentX = tx - nx * dot, tangentY = ty - ny * dot, tangentZ = tz - nz * dot;
        float tangentLength = length(tangentX, tangentY, tangentZ);
        if (tangentLength > 1.0e-12f) {
            tangentX /= tangentLength; tangentY /= tangentLength; tangentZ /= tangentLength;
        } else {
            tangentX = t1[0]; tangentY = t1[1]; tangentZ = t1[2];
        }
        float pxT = tangentX * t1[0] + tangentY * t1[1] + tangentZ * t1[2];
        float pyT = tangentX * t2[0] + tangentY * t2[1] + tangentZ * t2[2];
        float denom = Math.abs(pxT) + Math.abs(pyT);
        float d = denom <= 1.0e-20f ? 0.5f
                : -signNotZero(pyT) * 0.25f * (pxT / denom) + 0.5f + signNotZero(pyT) * 0.25f;
        int tangentByte = Math.max(0, Math.min(256, Math.round(d * 256.0f))) & 0xFF;
        return (tangentByte << 24) | encodedNormal;
    }

    private static float[] onbTangent(float nx, float ny, float nz) {
        float sign = nz >= 0.0f ? 1.0f : -1.0f;
        float a = -1.0f / (sign + nz);
        float b = nx * ny * a;
        return normalize(new float[]{
                1.0f + sign * nx * nx * a,
                sign * b,
                -sign * nx
        });
    }

    private static float signNotZero(float value) { return value >= 0.0f ? 1.0f : -1.0f; }
    private static float clamp(float value) { return Math.max(-1.0f, Math.min(1.0f, value)); }

    private record Basis(float nx, float ny, float nz, float tx, float ty, float tz,
                         float tangentHandedness) { }
}
