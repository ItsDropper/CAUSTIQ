package dev.itsdropper.caustiq.integration.nvidium;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Per-meshing-thread block metadata consumed by the extended terrain vertex encoder. */
public final class NvidiumIrisVertexContext {
    private static final ThreadLocal<BlockData> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Deque<BlockData>> PREVIOUS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicReference<Map<String, Integer>> BLOCK_IDS = new AtomicReference<>(Map.of());

    private NvidiumIrisVertexContext() { }

    public static Scope enter(BlockState state, BlockPos position, boolean fluid) {
        BlockData previous = CURRENT.get();
        CURRENT.set(createData(state, position, fluid));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** Pushes block metadata for the duration of a Sodium block/fluid mesh emission. */
    public static void push(BlockState state, BlockPos position, boolean fluid) {
        Deque<BlockData> previous = PREVIOUS.get();
        BlockData current = CURRENT.get();
        previous.push(current == null ? BlockData.EMPTY : current);
        CURRENT.set(createData(state, position, fluid));
    }

    /** Restores the previous block metadata after Sodium finishes emitting the model. */
    public static void pop() {
        Deque<BlockData> previous = PREVIOUS.get();
        if (previous.isEmpty()) {
            CURRENT.remove();
            PREVIOUS.remove();
            return;
        }
        BlockData restored = previous.pop();
        if (restored == BlockData.EMPTY) CURRENT.remove();
        else CURRENT.set(restored);
        if (previous.isEmpty()) PREVIOUS.remove();
    }

    private static BlockData createData(BlockState state, BlockPos position, boolean fluid) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(position, "position");
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String, Integer> mappings = BLOCK_IDS.get();
        int blockId = mappings.isEmpty()
                ? BuiltInRegistries.BLOCK.getId(state.getBlock())
                : mappings.getOrDefault(stateKey(state, key), mappings.getOrDefault(key, -1));
        return new BlockData(blockId, fluid ? 1 : 0, state.getLightEmission(),
                position.getX() & 15, position.getY() & 15, position.getZ() & 15);
    }

    /** Replaces registry fallback IDs with the selected pack's block.properties IDs. */
    public static boolean setBlockIds(Map<String, Integer> blockIds) {
        Map<String, Integer> replacement = Map.copyOf(blockIds);
        return !BLOCK_IDS.getAndSet(replacement).equals(replacement);
    }

    private static String stateKey(BlockState state, String blockId) {
        if (state.getValues().isEmpty()) return blockId;
        TreeMap<String, String> properties = new TreeMap<>();
        state.getValues().forEach((property, value) ->
                properties.put(property.getName(), String.valueOf(value)));
        StringBuilder key = new StringBuilder(blockId).append('[');
        properties.forEach((name, value) -> {
            if (key.charAt(key.length() - 1) != '[') key.append(',');
            key.append(name).append('=').append(value);
        });
        return key.append(']').toString();
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
