package io.ariadne.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import io.ariadne.adapter.reactor.AriadneReactorAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Benchmark comparing async hopping overhead in Project Reactor across three modes:
 * <ol>
 *   <li><b>BASELINE:</b> Raw Reactor execution with no tracking or debugging hooks.</li>
 *   <li><b>ARIADNE:</b> Lightweight causal propagation via {@code Schedulers.onScheduleHook}.</li>
 *   <li><b>OPERATOR_DEBUG:</b> Reactor's native {@code Hooks.onOperatorDebug()} (stack trace capture on assembly).</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class ReactorComparisonBenchmark {

    @Param({"BASELINE", "ARIADNE", "OPERATOR_DEBUG"})
    private String mode;

    @Setup(Level.Trial)
    public void setupTrial() {
        switch (mode) {
            case "BASELINE" -> {
                AriadneReactorAdapter.uninstall();
                Hooks.resetOnOperatorDebug();
            }
            case "ARIADNE" -> {
                Hooks.resetOnOperatorDebug();
                AriadneReactorAdapter.install();
            }
            case "OPERATOR_DEBUG" -> {
                AriadneReactorAdapter.uninstall();
                Hooks.onOperatorDebug();
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        AriadneReactorAdapter.uninstall();
        Hooks.resetOnOperatorDebug();
    }

    @Benchmark
    public Integer monoAsyncHop() {
        return Mono.just(42)
                .publishOn(Schedulers.parallel())
                .map(v -> v * 2)
                .block();
    }

    @Benchmark
    public Integer monoMultiHopChain() {
        return Mono.just(10)
                .publishOn(Schedulers.parallel())
                .map(v -> v + 1)
                .publishOn(Schedulers.boundedElastic())
                .map(v -> v * 2)
                .publishOn(Schedulers.parallel())
                .map(v -> v + 5)
                .block();
    }

    @Benchmark
    public Long fluxStreamAsyncHop() {
        return Flux.range(1, 100)
                .publishOn(Schedulers.parallel())
                .map(v -> (long) v * 2)
                .reduce(0L, Long::sum)
                .block();
    }
}
