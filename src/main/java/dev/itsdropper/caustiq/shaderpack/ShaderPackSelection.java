package dev.itsdropper.caustiq.shaderpack;

import java.util.List;
import java.util.Objects;

/** Wraparound navigation for the currently discovered shader-pack list. */
public final class ShaderPackSelection {
    private ShaderPackSelection() { }

    /**
     * Returns the next selected index when moving in the requested direction.
     * A missing selection starts at the first or last entry, depending on direction.
     */
    public static int nextIndex(List<ShaderPackDescriptor> packs, String selectedPackId, int direction) {
        Objects.requireNonNull(packs, "packs");
        if (direction == 0) throw new IllegalArgumentException("Navigation direction must not be zero");
        if (packs.isEmpty()) return -1;

        int current = -1;
        for (int index = 0; index < packs.size(); index++) {
            if (packs.get(index).id().equals(selectedPackId)) {
                current = index;
                break;
            }
        }

        if (current < 0) return direction > 0 ? 0 : packs.size() - 1;
        return Math.floorMod(current + Integer.signum(direction), packs.size());
    }
}
