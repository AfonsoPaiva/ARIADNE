package io.ariadne.benchmark;

import java.lang.instrument.Instrumentation;
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

import io.ariadne.agent.AriadneAgent;
import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * Microbenchmark measuring dispatch overhead through bytecode instrumentation (Java Agent path)
 * vs raw JDK executions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class AgentDispatchBenchmark {

    private ExecutorService pool;

    @Setup
    public void setup() {
        System.setProperty("net.bytebuddy.experimental", "true");
        Instrumentation inst = ByteBuddyAgent.install();
        AriadneAgent.install(inst);
        pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @TearDown
    public void tearDown() {
        pool.shutdown();
        long hops = io.ariadne.core.AriadneMetrics.getHopsSpawned();
        System.out.printf("%n[AgentDispatchBenchmark] Verified Causal Interception: AriadneMetrics.getHopsSpawned() = %,d hops%n", hops);
        if (hops <= 0) {
            throw new IllegalStateException("CRITICAL PROOF FAILURE: Ariadne Agent was not attached! getHopsSpawned() is 0.");
        }
    }

    @Benchmark
    public Integer agentInstrumentedThreadPoolSubmit() throws Exception {
        return pool.submit(() -> 42).get();
    }

    @Benchmark
    public Integer agentInstrumentedCompletableFutureAsync() {
        return CompletableFuture.supplyAsync(() -> 42, pool).join();
    }

    @Benchmark
    public Integer agentInstrumentedCompletableFutureMultiHop() {
        return CompletableFuture.supplyAsync(() -> 1, pool)
                .thenApplyAsync(x -> x + 1, pool)
                .thenApplyAsync(x -> x + 2, pool)
                .join();
    }
}
