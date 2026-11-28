package dev.itsdropper.caustiq.shaderpack;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Coalesces CPU-only parsing for one selected pack and scan snapshot.
 * Failed or cancelled parses are not cached permanently, so an explicit reload can retry.
 */
final class ShaderPackModelPreloadCache<T> {
    private String packId = "";
    private Object scanSnapshot;
    private CompletableFuture<T> future;

    synchronized CompletableFuture<T> getOrStart(
            String requestedPackId,
            Object requestedScanSnapshot,
            Supplier<CompletableFuture<T>> starter
    ) {
        if (requestedPackId == null || requestedPackId.isBlank()) {
            throw new IllegalArgumentException("A selected shader-pack ID is required.");
        }
        Objects.requireNonNull(requestedScanSnapshot, "requestedScanSnapshot");
        Objects.requireNonNull(starter, "starter");

        if (isReusable(requestedPackId, requestedScanSnapshot)) {
            return future;
        }

        CompletableFuture<T> started = Objects.requireNonNull(
                starter.get(), "The shader-pack parser must return a future.");
        packId = requestedPackId;
        scanSnapshot = requestedScanSnapshot;
        future = started;
        return started;
    }

    synchronized boolean isCurrent(
            String requestedPackId,
            Object requestedScanSnapshot,
            CompletableFuture<T> requestedFuture
    ) {
        return future != null
                && future == requestedFuture
                && packId.equals(requestedPackId)
                && scanSnapshot == requestedScanSnapshot;
    }

    synchronized void invalidate() {
        packId = "";
        scanSnapshot = null;
        future = null;
    }

    private boolean isReusable(String requestedPackId, Object requestedScanSnapshot) {
        return future != null
                && packId.equals(requestedPackId)
                && scanSnapshot == requestedScanSnapshot
                && !future.isCompletedExceptionally()
                && !future.isCancelled();
    }
}
