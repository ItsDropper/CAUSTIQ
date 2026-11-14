package dev.itsdropper.caustiq.integration.nvidium;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observes the actual Nvidium terrain-renderer entrypoints without linking
 * CAUSTIQ against Nvidium implementation classes.
 */
public final class NvidiumRenderBridge {
    private static final AtomicLong STARTED_SOLID = new AtomicLong();
    private static final AtomicLong COMPLETED_SOLID = new AtomicLong();
    private static final AtomicLong STARTED_TRANSLUCENT = new AtomicLong();
    private static final AtomicLong COMPLETED_TRANSLUCENT = new AtomicLong();

    private NvidiumRenderBridge() { }

    public static void solidPassStarted() { STARTED_SOLID.incrementAndGet(); }
    public static void solidPassCompleted() { COMPLETED_SOLID.incrementAndGet(); }
    public static void translucentPassStarted() { STARTED_TRANSLUCENT.incrementAndGet(); }
    public static void translucentPassCompleted() { COMPLETED_TRANSLUCENT.incrementAndGet(); }

    public static Snapshot snapshot() {
        return new Snapshot(
                STARTED_SOLID.get(),
                COMPLETED_SOLID.get(),
                STARTED_TRANSLUCENT.get(),
                COMPLETED_TRANSLUCENT.get());
    }

    static void resetForTests() {
        STARTED_SOLID.set(0);
        COMPLETED_SOLID.set(0);
        STARTED_TRANSLUCENT.set(0);
        COMPLETED_TRANSLUCENT.set(0);
    }

    public record Snapshot(
            long startedSolidPasses,
            long completedSolidPasses,
            long startedTranslucentPasses,
            long completedTranslucentPasses) {
        public boolean hasCompletedTerrainPass() {
            return completedSolidPasses > 0;
        }

        public boolean hasUnfinishedPass() {
            return startedSolidPasses > completedSolidPasses
                    || startedTranslucentPasses > completedTranslucentPasses;
        }
    }
}
