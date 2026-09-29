package io.ariadne.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * CLI launcher for Ariadne JMH microbenchmarks.
 * <p>
 * Can be run directly via:
 * <pre>
 * java -jar ariadne-benchmarks/target/benchmarks.jar
 * </pre>
 * or programmatically from an IDE or build tool.
 */
public final class AriadneBenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include("io.ariadne.benchmark.*")
                .warmupIterations(5)
                .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(1))
                .measurementIterations(5)
                .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(1))
                .forks(3)
                .build();

        new Runner(opt).run();
    }
}
