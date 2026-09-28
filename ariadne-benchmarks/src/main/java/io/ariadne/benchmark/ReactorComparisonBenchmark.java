package io.ariadne.benchmark;

import io.ariadne.adapter.reactor.AriadneReactorAdapter;
import org.openjdk.jmh.annotations.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeUnit;

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
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
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
