package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackModelPreloadCacheTest {
    @Test
    void coalescesRequestsForTheSamePackAndScanSnapshot() {
        ShaderPackModelPreloadCache<String> cache = new ShaderPackModelPreloadCache<>();
        Object snapshot = new Object();
        AtomicInteger starts = new AtomicInteger();

        CompletableFuture<String> first = cache.getOrStart("Lumina", snapshot, () -> {
            starts.incrementAndGet();
            return new CompletableFuture<>();
        });
        CompletableFuture<String> second = cache.getOrStart("Lumina", snapshot, () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture("duplicate");
        });

        assertSame(first, second);
        assertEquals(1, starts.get());
        assertTrue(cache.isCurrent("Lumina", snapshot, first));
    }

    @Test
    void aDifferentPackOrScanSnapshotStartsANewParseAndMakesOldCompletionStale() {
        ShaderPackModelPreloadCache<String> cache = new ShaderPackModelPreloadCache<>();
        Object firstSnapshot = new Object();
        Object secondSnapshot = new Object();
        CompletableFuture<String> first = new CompletableFuture<>();

        cache.getOrStart("Lumina", firstSnapshot, () -> first);
        CompletableFuture<String> second = cache.getOrStart(
                "Stillwater", secondSnapshot, () -> CompletableFuture.completedFuture("new"));

        assertFalse(cache.isCurrent("Lumina", firstSnapshot, first));
        assertTrue(cache.isCurrent("Stillwater", secondSnapshot, second));
        assertNotSame(first, second);
    }

    @Test
    void failedParseIsRetriedInsteadOfReturningThePermanentlyFailedFuture() {
        ShaderPackModelPreloadCache<String> cache = new ShaderPackModelPreloadCache<>();
        Object snapshot = new Object();
        CompletableFuture<String> failed = CompletableFuture.failedFuture(
                new IllegalStateException("temporary read failure"));
        cache.getOrStart("Lumina", snapshot, () -> failed);

        CompletableFuture<String> retry = cache.getOrStart(
                "Lumina", snapshot, () -> CompletableFuture.completedFuture("parsed"));

        assertNotSame(failed, retry);
        assertEquals("parsed", retry.join());
        assertTrue(cache.isCurrent("Lumina", snapshot, retry));
    }

    @Test
    void cancelledParseIsAlsoRetryable() {
        ShaderPackModelPreloadCache<String> cache = new ShaderPackModelPreloadCache<>();
        Object snapshot = new Object();
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        cache.getOrStart("Lumina", snapshot, () -> cancelled);

        CompletableFuture<String> retry = cache.getOrStart(
                "Lumina", snapshot, () -> CompletableFuture.completedFuture("parsed"));

        assertNotSame(cancelled, retry);
        assertEquals("parsed", retry.join());
    }

    @Test
    void invalidationForcesANewParseForTheSamePackAndSnapshot() {
        ShaderPackModelPreloadCache<String> cache = new ShaderPackModelPreloadCache<>();
        Object snapshot = new Object();
        CompletableFuture<String> first = CompletableFuture.completedFuture("old");
        cache.getOrStart("Lumina", snapshot, () -> first);

        cache.invalidate();
        CompletableFuture<String> second = cache.getOrStart(
                "Lumina", snapshot, () -> CompletableFuture.completedFuture("fresh"));

        assertNotSame(first, second);
        assertEquals("fresh", second.join());
        assertFalse(cache.isCurrent("Lumina", snapshot, first));
    }
}
