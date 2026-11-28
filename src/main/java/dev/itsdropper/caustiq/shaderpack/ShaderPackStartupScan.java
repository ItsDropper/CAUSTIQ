package dev.itsdropper.caustiq.shaderpack;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the required first shader-pack discovery away from the client thread,
 * while allowing the existing pre-world compatibility hook to await and publish it.
 */
public final class ShaderPackStartupScan {
    private final CompletableFuture<Outcome> completion;
    private boolean published;

    private ShaderPackStartupScan(CompletableFuture<Outcome> completion) {
        this.completion = completion;
    }

    public static ShaderPackStartupScan start(Supplier<ShaderPackScanResult> scanner) {
        return start(scanner, ForkJoinPool.commonPool());
    }

    static ShaderPackStartupScan start(Supplier<ShaderPackScanResult> scanner, Executor worker) {
        Objects.requireNonNull(scanner, "scanner");
        Objects.requireNonNull(worker, "worker");
        CompletableFuture<Outcome> completion = CompletableFuture
                .supplyAsync(scanner, worker)
                .handle((result, failure) -> {
                    Throwable cause = unwrap(failure);
                    if (cause != null) return new Outcome(null, cause);
                    if (result == null) {
                        return new Outcome(null, new IllegalStateException("Startup scanner returned no result."));
                    }
                    return new Outcome(result, null);
                });
        return new ShaderPackStartupScan(completion);
    }

    /** Schedules publication on the supplied client executor when scanning finishes. */
    public void publishWhenReady(Executor clientExecutor, Consumer<Outcome> publisher) {
        Objects.requireNonNull(clientExecutor, "clientExecutor");
        Objects.requireNonNull(publisher, "publisher");
        completion.thenAccept(outcome ->
                clientExecutor.execute(() -> publishOnce(outcome, publisher)));
    }

    /**
     * Waits for the worker and publishes on the calling thread. Used only at
     * Nvidium's existing pre-world compatibility decision point.
     */
    public Outcome awaitAndPublish(Consumer<Outcome> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        Outcome outcome = completion.join();
        publishOnce(outcome, publisher);
        return outcome;
    }

    public boolean isDone() {
        return completion.isDone();
    }

    private synchronized void publishOnce(Outcome outcome, Consumer<Outcome> publisher) {
        if (published) return;
        published = true;
        publisher.accept(outcome);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    public record Outcome(ShaderPackScanResult result, Throwable failure) {
        public Outcome {
            if ((result == null) == (failure == null)) {
                throw new IllegalArgumentException("A startup scan outcome must contain either a result or a failure.");
            }
        }

        public boolean succeeded() {
            return result != null;
        }
    }
}
