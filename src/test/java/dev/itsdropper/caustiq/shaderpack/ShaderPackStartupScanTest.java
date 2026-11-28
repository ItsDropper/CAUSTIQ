package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackStartupScanTest {
    private static final ShaderPackScanResult RESULT =
            new ShaderPackScanResult(List.of(), List.of(), 0);

    @Test
    void backgroundDiscoveryCanBeAwaitedAtThePreWorldGateAndPublishesOnlyOnce() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            ShaderPackStartupScan startup = ShaderPackStartupScan.start(() -> {
                started.countDown();
                awaitRelease(release);
                return RESULT;
            }, worker);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(startup.isDone());

            AtomicInteger publicationCount = new AtomicInteger();
            AtomicReference<ShaderPackStartupScan.Outcome> published = new AtomicReference<>();
            java.util.function.Consumer<ShaderPackStartupScan.Outcome> publisher = outcome -> {
                publicationCount.incrementAndGet();
                published.set(outcome);
            };
            startup.publishWhenReady(Runnable::run, publisher);

            release.countDown();
            ShaderPackStartupScan.Outcome outcome = startup.awaitAndPublish(publisher);

            assertTrue(startup.isDone());
            assertTrue(outcome.succeeded());
            assertSame(RESULT, outcome.result());
            assertSame(outcome, published.get());
            assertEquals(1, publicationCount.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }


    @Test
    void dispatchesCompletionThroughTheProvidedClientExecutor() {
        java.util.List<Runnable> queuedTasks = new java.util.ArrayList<>();
        AtomicInteger publicationCount = new AtomicInteger();
        ShaderPackStartupScan startup = ShaderPackStartupScan.start(() -> RESULT, Runnable::run);

        startup.publishWhenReady(queuedTasks::add, outcome -> {
            assertSame(RESULT, outcome.result());
            publicationCount.incrementAndGet();
        });

        assertEquals(1, queuedTasks.size());
        assertEquals(0, publicationCount.get());
        queuedTasks.remove(0).run();
        assertEquals(1, publicationCount.get());
    }

    @Test
    void reportsWorkerFailureWithoutInventingPartialResults() {
        ShaderPackStartupScan startup = ShaderPackStartupScan.start(
                () -> { throw new IllegalStateException("unreadable shader-pack directory"); },
                Runnable::run);
        AtomicInteger publicationCount = new AtomicInteger();

        ShaderPackStartupScan.Outcome outcome = startup.awaitAndPublish(ignored -> publicationCount.incrementAndGet());

        assertFalse(outcome.succeeded());
        assertNull(outcome.result());
        assertTrue(outcome.failure() instanceof IllegalStateException);
        assertEquals("unreadable shader-pack directory", outcome.failure().getMessage());
        assertEquals(1, publicationCount.get());
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test to release startup discovery.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Startup discovery test was interrupted.", exception);
        }
    }
}
