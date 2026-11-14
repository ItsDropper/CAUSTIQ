package dev.itsdropper.caustiq.integration.nvidium;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Per-meshing-thread block metadata consumed by the extended terrain vertex encoder. */
public final class NvidiumIrisVertexContext {
    private static final ThreadLocal<BlockData> CURRENT = new ThreadLocal<>();
    private static final AtomicReference<Map<String, Integer>> BLOCK_IDS = new AtomicReference<>(Map.of());

    private NvidiumIrisVertexContext() { }

    public static Scope enter(BlockState state, BlockPos position, boolean fluid) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(position, "position");
        BlockData previous = CURRENT.get();
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int blockId = BLOCK_IDS.get().getOrDefault(key, BuiltInRegistries.BLOCK.getId(state.getBlock()));
        CURRENT.set(new BlockData(blockId, fluid ? 1 : 0, state.getLightEmission(),
                position.getX() & 15, position.getY() & 15, position.getZ() & 15));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** Replaces registry fallback IDs with the selected pack's block.properties IDs. */
    public static void setBlockIds(Map<String, Integer> blockIds) {
        BLOCK_IDS.set(Map.copyOf(blockIds));
    }

    static BlockData current() {
        BlockData data = CURRENT.get();
        return data == null ? BlockData.EMPTY : data;
    }

    record BlockData(int blockId, int renderType, int emission, int localX, int localY, int localZ) {
        private static final BlockData EMPTY = new BlockData(-1, 0, 0, 0, 0, 0);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
