package dev.itsdropper.caustiq.integration.nvidium;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime evidence from the actual Nvidium renderer and CAUSTIQ integration
 * boundaries. Mod presence alone is deliberately not treated as proof.
 */
public final class NvidiumRenderBridge {
    private static final AtomicLong STARTED_SOLID = new AtomicLong();
    private static final AtomicLong COMPLETED_SOLID = new AtomicLong();
    private static final AtomicLong STARTED_TRANSLUCENT = new AtomicLong();
    private static final AtomicLong COMPLETED_TRANSLUCENT = new AtomicLong();
    private static final AtomicLong ADAPTED_PRIMARY = new AtomicLong();
    private static final AtomicLong ADAPTED_TRANSLUCENT = new AtomicLong();
    private static final AtomicLong ADAPTED_SHADOW = new AtomicLong();
    private static final AtomicLong PRIMARY_RASTER_CALLS = new AtomicLong();
    private static final AtomicLong PRIMARY_RASTER_REGIONS = new AtomicLong();
    private static final AtomicLong TRANSLUCENT_RASTER_CALLS = new AtomicLong();
    private static final AtomicLong TRANSLUCENT_RASTER_REGIONS = new AtomicLong();
    private static final AtomicLong GBUFFER_ATTACHMENTS = new AtomicLong();
    private static final AtomicLong GBUFFER_DRAWS = new AtomicLong();
    private static final AtomicLong GBUFFER_NONCLEAR_READBACKS = new AtomicLong();
    private static final AtomicLong SHADOW_FRAMES = new AtomicLong();
    private static final AtomicLong SHADOW_NONCLEAR_READBACKS = new AtomicLong();
    private static final AtomicLong FULLSCREEN_PASSES = new AtomicLong();
    private static final AtomicLong COMPUTE_PASSES = new AtomicLong();
    private static volatile Object worldRenderer;

    private NvidiumRenderBridge() { }

    public static void attachWorldRenderer(Object renderer) { worldRenderer = renderer; }

    /** Recompiles Nvidium's task/mesh/fragment programs through the registered CAUSTIQ adapters. */
    public static boolean reloadShaders() {
        Object renderer = worldRenderer;
        if (renderer == null) return false;
        try {
            renderer.getClass().getMethod("reloadShaders").invoke(renderer);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    public static void solidPassStarted() { STARTED_SOLID.incrementAndGet(); }
    public static void solidPassCompleted() { COMPLETED_SOLID.incrementAndGet(); }
    public static void translucentPassStarted() { STARTED_TRANSLUCENT.incrementAndGet(); }
    public static void translucentPassCompleted() { COMPLETED_TRANSLUCENT.incrementAndGet(); }

    public static void shaderAdapted(NvidiumShaderProgramBridge.PipelineKind pipeline) {
        switch (pipeline) {
            case PRIMARY -> ADAPTED_PRIMARY.incrementAndGet();
            case TRANSLUCENT -> ADAPTED_TRANSLUCENT.incrementAndGet();
            case SHADOW, SHADOW_CUTOUT, SHADOW_TRANSLUCENT -> ADAPTED_SHADOW.incrementAndGet();
        }
    }

    public static void primaryRasterized(int regionCount) {
        if (regionCount <= 0) return;
        PRIMARY_RASTER_CALLS.incrementAndGet();
        PRIMARY_RASTER_REGIONS.addAndGet(regionCount);
    }

    public static void translucentRasterized(int regionCount) {
        if (regionCount <= 0) return;
        TRANSLUCENT_RASTER_CALLS.incrementAndGet();
        TRANSLUCENT_RASTER_REGIONS.addAndGet(regionCount);
    }

    public static void gbufferAttached() { GBUFFER_ATTACHMENTS.incrementAndGet(); }
    public static void gbufferDrawCompleted() { GBUFFER_DRAWS.incrementAndGet(); }
    public static void gbufferPixelsObserved() { GBUFFER_NONCLEAR_READBACKS.incrementAndGet(); }
    public static void shadowPixelsObserved() { SHADOW_NONCLEAR_READBACKS.incrementAndGet(); }
    public static void shadowFrameRendered(int regionCount) {
        if (regionCount > 0) SHADOW_FRAMES.incrementAndGet();
    }
    public static void fullscreenPassExecuted(boolean compute) {
        FULLSCREEN_PASSES.incrementAndGet();
        if (compute) COMPUTE_PASSES.incrementAndGet();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                STARTED_SOLID.get(), COMPLETED_SOLID.get(),
                STARTED_TRANSLUCENT.get(), COMPLETED_TRANSLUCENT.get(),
                ADAPTED_PRIMARY.get(), ADAPTED_TRANSLUCENT.get(), ADAPTED_SHADOW.get(),
                PRIMARY_RASTER_CALLS.get(), PRIMARY_RASTER_REGIONS.get(),
                TRANSLUCENT_RASTER_CALLS.get(), TRANSLUCENT_RASTER_REGIONS.get(),
                GBUFFER_ATTACHMENTS.get(), GBUFFER_DRAWS.get(), GBUFFER_NONCLEAR_READBACKS.get(),
                SHADOW_FRAMES.get(), SHADOW_NONCLEAR_READBACKS.get(), FULLSCREEN_PASSES.get(), COMPUTE_PASSES.get());
    }

    static void resetForTests() {
        STARTED_SOLID.set(0);
        COMPLETED_SOLID.set(0);
        STARTED_TRANSLUCENT.set(0);
        COMPLETED_TRANSLUCENT.set(0);
        ADAPTED_PRIMARY.set(0);
        ADAPTED_TRANSLUCENT.set(0);
        ADAPTED_SHADOW.set(0);
        PRIMARY_RASTER_CALLS.set(0);
        PRIMARY_RASTER_REGIONS.set(0);
        TRANSLUCENT_RASTER_CALLS.set(0);
        TRANSLUCENT_RASTER_REGIONS.set(0);
        GBUFFER_ATTACHMENTS.set(0);
        GBUFFER_DRAWS.set(0);
        GBUFFER_NONCLEAR_READBACKS.set(0);
        SHADOW_FRAMES.set(0);
        SHADOW_NONCLEAR_READBACKS.set(0);
        FULLSCREEN_PASSES.set(0);
        COMPUTE_PASSES.set(0);
        worldRenderer = null;
    }

    public record Snapshot(
            long startedSolidPasses,
            long completedSolidPasses,
            long startedTranslucentPasses,
            long completedTranslucentPasses,
            long adaptedPrimaryPrograms,
            long adaptedTranslucentPrograms,
            long adaptedShadowPrograms,
            long primaryRasterCalls,
            long primaryRasterRegions,
            long translucentRasterCalls,
            long translucentRasterRegions,
            long successfulGbufferAttachments,
            long completedGbufferDraws,
            long gbufferNonClearReadbacks,
            long shadowFrames,
            long shadowNonClearReadbacks,
            long fullscreenPasses,
            long computePasses) {
        /** Retains the original four-counter constructor for focused unit tests. */
        public Snapshot(long startedSolidPasses, long completedSolidPasses,
                        long startedTranslucentPasses, long completedTranslucentPasses) {
            this(startedSolidPasses, completedSolidPasses, startedTranslucentPasses, completedTranslucentPasses,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean hasCompletedSolidRenderCall() { return completedSolidPasses > 0; }
        public boolean hasUnfinishedPass() {
            return startedSolidPasses > completedSolidPasses
                    || startedTranslucentPasses > completedTranslucentPasses;
        }

        public boolean provesTerrainPipelineExecuted() {
            return adaptedPrimaryPrograms > 0
                    && primaryRasterCalls > 0 && primaryRasterRegions > 0
                    && successfulGbufferAttachments > 0 && completedGbufferDraws > 0
                    && gbufferNonClearReadbacks > 0
                    && completedSolidPasses > 0;
        }

        public boolean provesShaderPassesExecuted() {
            return fullscreenPasses > 0;
        }

        public boolean provesShadowPipelineExecuted() {
            return adaptedShadowPrograms > 0 && shadowFrames > 0 && shadowNonClearReadbacks > 0;
        }
    }
}
