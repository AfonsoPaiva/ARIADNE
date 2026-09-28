package io.ariadne.benchmark;

import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneFunction;
import io.ariadne.core.AriadneSupplier;
import io.ariadne.core.Link;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.*;

/**
 * Microbenchmark measuring the overhead of wrapping and propagating causality
 * across standard platform ThreadPoolExecutors and Java 21 Virtual Threads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ThreadPoolPropagationBenchmark {

    private ExecutorService platformPool;
    private ExecutorService virtualThreadExecutor;
    private Link rootLink;

    @Setup
    public void setup() {
        platformPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        rootLink = new Link(null, 100, Thread.currentThread().threadId());
        AriadneContext.set(rootLink);
    }

    @TearDown
    public void tearDown() {
        platformPool.shutdown();
        virtualThreadExecutor.shutdown();
        AriadneContext.clear();
    }

    @Benchmark
    public Integer platformPoolBaseline() throws Exception {
        return platformPool.submit(() -> 42).get();
    }

    @Benchmark
    public Integer platformPoolAriadneWrapped() throws Exception {
        return platformPool.submit(AriadneCallable.wrap(() -> 42)).get();
    }

    @Benchmark
    public Integer virtualThreadBaseline() throws Exception {
        return virtualThreadExecutor.submit(() -> 42).get();
    }

    @Benchmark
    public Integer virtualThreadAriadneWrapped() throws Exception {
        return virtualThreadExecutor.submit(AriadneCallable.wrap(() -> 42)).get();
    }

    @Benchmark
    public Integer completableFutureMultiHopBaseline() {
        return CompletableFuture.supplyAsync(() -> 1, platformPool)
                .thenApplyAsync(x -> x + 1, platformPool)
                .thenApplyAsync(x -> x + 2, platformPool)
                .join();
    }

    @Benchmark
    public Integer completableFutureMultiHopWrapped() {
        // Explicit functional wrapping baseline comparison
        return CompletableFuture.supplyAsync(AriadneSupplier.wrap(() -> 1), platformPool)
                .thenApplyAsync(AriadneFunction.wrap(x -> x + 1), platformPool)
                .thenApplyAsync(AriadneFunction.wrap(x -> x + 2), platformPool)
                .join();
    }
}
