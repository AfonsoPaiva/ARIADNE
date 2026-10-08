package io.ariadne.benchmark;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * Head-to-head JMH microbenchmark comparing Ariadne with OpenTelemetry Java SDK
 * under identical JVM and hardware conditions:
 * <ul>
 *   <li>Context reading (ThreadLocal carrier retrieval)</li>
 *   <li>Context attaching & scoping (try-with-resources auto-close)</li>
 *   <li>Context hopping & Span allocation</li>
 *   <li>Synchronous wrapping (Runnable / Callable)</li>
 *   <li>Thread pool propagation (Platform thread pool & Java 21 Virtual Threads)</li>
 *   <li>CompletableFuture single-hop async dispatch</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class OpenTelemetryComparisonBenchmark {

    // Ariadne state
    private int siteId;
    private Link sampleLink;

    // OpenTelemetry state
    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;
    private Tracer tracer;
    private Span sampleSpan;
    private Context otelContext;

    // Tasks and Executors
    private Runnable noopRunnable;
    private Callable<Integer> noopCallable;
    private ExecutorService platformPool;
    private ExecutorService virtualThreadExecutor;

    @Setup
    public void setup() {
        // Ariadne setup
        siteId = SiteRegistry.getOrRegister("Benchmark.callSite");
        sampleLink = new Link(null, siteId, Thread.currentThread().threadId());
        AriadneContext.set(sampleLink);

        // OpenTelemetry setup (in-memory standard SDK without network exporter)
        tracerProvider = SdkTracerProvider.builder().build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        tracer = openTelemetry.getTracer("ariadne-benchmark");
        sampleSpan = tracer.spanBuilder("benchmark-span").startSpan();
        otelContext = Context.current().with(sampleSpan);

        // Common tasks
        noopRunnable = () -> {};
        noopCallable = () -> 42;
        platformPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @TearDown
    public void tearDown() {
        AriadneContext.clear();
        sampleSpan.end();
        tracerProvider.close();
        platformPool.shutdown();
        virtualThreadExecutor.shutdown();
    }

    // -------------------------------------------------------------------------
    // 1. Context Read (nanoseconds)
    // -------------------------------------------------------------------------

    @Benchmark
    public Link ariadne_contextReadCurrent() {
        return AriadneContext.current();
    }

    @Benchmark
    public Context otel_contextReadCurrent() {
        return Context.current();
    }

    // -------------------------------------------------------------------------
    // 2. Context Attach & Scope (try-with-resources)
    // -------------------------------------------------------------------------

    @Benchmark
    public void ariadne_contextAttachAndScope(Blackhole bh) {
        try (AriadneContext.Scope scope = AriadneContext.attach(sampleLink)) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void otel_contextAttachAndScope(Blackhole bh) {
        try (io.opentelemetry.context.Scope scope = otelContext.makeCurrent()) {
            bh.consume(scope);
        }
    }

    // -------------------------------------------------------------------------
    // 3. Context Hop & Node / Span Allocation
    // -------------------------------------------------------------------------

    @Benchmark
    public Link ariadne_directLinkAllocation() {
        return new Link(sampleLink, siteId, 1L);
    }

    @Benchmark
    public Link ariadne_contextSpawn() {
        return AriadneContext.spawn(siteId);
    }

    @Benchmark
    public Context otel_contextWithSpan() {
        return otelContext.with(sampleSpan);
    }

    @Benchmark
    public void otel_spanCreationAndEnd(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        span.end();
        bh.consume(span);
    }

    // -------------------------------------------------------------------------
    // 4. Synchronous Wrap and Run (Runnable / Callable)
    // -------------------------------------------------------------------------

    @Benchmark
    public void ariadne_synchronousRunnableWrapAndRun() {
        AriadneRunnable.wrap(noopRunnable, sampleLink).run();
    }

    @Benchmark
    public void otel_synchronousRunnableWrapAndRun() {
        otelContext.wrap(noopRunnable).run();
    }

    @Benchmark
    public Integer ariadne_synchronousCallableWrapAndCall() throws Exception {
        return AriadneCallable.wrap(noopCallable, sampleLink).call();
    }

    @Benchmark
    public Integer otel_synchronousCallableWrapAndCall() throws Exception {
        return otelContext.wrap(noopCallable).call();
    }

    // -------------------------------------------------------------------------
    // 5. Asynchronous Thread Pool Propagation (Platform & Virtual Threads)
    // -------------------------------------------------------------------------

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer baseline_platformPoolSubmit() throws Exception {
        return platformPool.submit(() -> 42).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer ariadne_platformPoolSubmit() throws Exception {
        return platformPool.submit(AriadneCallable.wrap(() -> 42, sampleLink)).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer otel_platformPoolSubmit() throws Exception {
        return platformPool.submit(otelContext.wrap(() -> 42)).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer baseline_virtualThreadSubmit() throws Exception {
        return virtualThreadExecutor.submit(() -> 42).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer ariadne_virtualThreadSubmit() throws Exception {
        return virtualThreadExecutor.submit(AriadneCallable.wrap(() -> 42, sampleLink)).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Integer otel_virtualThreadSubmit() throws Exception {
        return virtualThreadExecutor.submit(otelContext.wrap(() -> 42)).get();
    }

    // -------------------------------------------------------------------------
    // 6. CompletableFuture Single-Hop
    // -------------------------------------------------------------------------

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Void baseline_completableFutureSingleHop() throws Exception {
        return CompletableFuture.runAsync(noopRunnable, platformPool).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Void ariadne_completableFutureSingleHop() throws Exception {
        return CompletableFuture.runAsync(AriadneRunnable.wrap(noopRunnable, sampleLink), platformPool).get();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Void otel_completableFutureSingleHop() throws Exception {
        return CompletableFuture.runAsync(otelContext.wrap(noopRunnable), platformPool).get();
    }
}
