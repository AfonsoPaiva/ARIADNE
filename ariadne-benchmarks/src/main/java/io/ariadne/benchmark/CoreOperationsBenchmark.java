package io.ariadne.benchmark;

import java.util.concurrent.Callable;
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

/**
 * Microbenchmark measuring isolated core operations in nanoseconds without
 * operating system thread-scheduling noise:
 * <ul>
 *   <li>{@link AriadneContext#current()} context retrieval</li>
 *   <li>{@link Link} direct constructor allocation (TLAB)</li>
 *   <li>{@link AriadneContext#spawn(int)} context hop creation</li>
 *   <li>{@link AriadneContext#attach(Link)} scoped context binding & restoration</li>
 *   <li>{@link AriadneRunnable#wrap(Runnable, Link)} & synchronous execution</li>
 *   <li>{@link SiteRegistry#getOrRegister(String)} cached call-site lookup</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class CoreOperationsBenchmark {

    private int siteId;
    private Link sampleLink;
    private Runnable noopRunnable;
    private Callable<Integer> noopCallable;

    @Setup
    public void setup() {
        siteId = SiteRegistry.getOrRegister("Benchmark.callSite");
        sampleLink = new Link(null, siteId, Thread.currentThread().threadId());
        AriadneContext.set(sampleLink);
        noopRunnable = () -> {};
        noopCallable = () -> 42;
    }

    @TearDown
    public void tearDown() {
        AriadneContext.clear();
    }

    @Benchmark
    public Link contextReadCurrent() {
        return AriadneContext.current();
    }

    @Benchmark
    public Link directLinkAllocation() {
        return new Link(sampleLink, siteId, 1L);
    }

    @Benchmark
    public Link contextSpawn() {
        return AriadneContext.spawn(siteId);
    }

    @Benchmark
    public void contextAttachAndScope(Blackhole bh) {
        try (AriadneContext.Scope scope = AriadneContext.attach(sampleLink)) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void synchronousRunnableWrapAndRun() {
        AriadneRunnable.wrap(noopRunnable, sampleLink).run();
    }

    @Benchmark
    public Integer synchronousCallableWrapAndCall() throws Exception {
        return AriadneCallable.wrap(noopCallable, sampleLink).call();
    }

    @Benchmark
    public int cachedSiteRegistryLookup() {
        return SiteRegistry.getOrRegister("Benchmark.callSite");
    }
}
