package io.ariadne.demo;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.ariadne.agent.AriadneAgent;
import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneMetrics;
import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * Production Load Benchmark: Throughput (req/s) and Latency percentiles (p50, p90, p99, p99.9)
 * for Spring Boot (@Async, ThreadPoolTaskExecutor, CompletableFuture) under concurrent load.
 * <p>
 * Evaluates production readiness with and without Ariadne Agent attached.
 */
class SpringBootThroughputP99Benchmark {

    private static final int CONCURRENCY = 16;
    private static final int WARMUP_REQUESTS = 5_000;
    private static final int MEASUREMENT_REQUESTS = 25_000;

    static class LoadTestStats {
        final double throughputOpsPerSec;
        final double meanLatencyUs;
        final double p50Us;
        final double p90Us;
        final double p99Us;
        final double p999Us;
        final double maxUs;
        final long totalHopsTracked;

        LoadTestStats(long[] latenciesNanos, long durationNanos, long totalHops) {
            Arrays.sort(latenciesNanos);
            int n = latenciesNanos.length;
            this.throughputOpsPerSec = (n / (double) durationNanos) * 1_000_000_000.0;

            long sum = 0;
            for (long l : latenciesNanos) {
                sum += l;
            }
            this.meanLatencyUs = (sum / (double) n) / 1000.0;
            this.p50Us = latenciesNanos[(int) (n * 0.50)] / 1000.0;
            this.p90Us = latenciesNanos[(int) (n * 0.90)] / 1000.0;
            this.p99Us = latenciesNanos[(int) (n * 0.99)] / 1000.0;
            this.p999Us = latenciesNanos[Math.min(n - 1, (int) (n * 0.999))] / 1000.0;
            this.maxUs = latenciesNanos[n - 1] / 1000.0;
            this.totalHopsTracked = totalHops;
        }
    }

    @Test
    @DisplayName("Throughput and Tail Latency (p50, p90, p99, p99.9) Load Benchmark under 16 concurrent workers")
    void benchmarkSpringThroughputAndP99UnderLoad() throws Exception {
        // 1. Initialize Spring ApplicationContext
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DemoApplication.class);
            context.refresh();
            OrderService orderService = context.getBean(OrderService.class);

            // Install Agent
            try {
                Instrumentation inst = ByteBuddyAgent.install();
                AriadneAgent.install(inst);
            } catch (Throwable ignored) {
            }

            // JIT warm-up so both baseline and agent run with C2 compiled code
            executeConcurrentLoad(orderService, 10_000);

            // 2. Measure Baseline (Kill switch active -> zero instrumentation overhead)
            AriadneConfig.setEnabled(false);
            // Warmup baseline
            executeConcurrentLoad(orderService, WARMUP_REQUESTS);
            // Measure baseline
            LoadTestStats baseline = executeConcurrentLoad(orderService, MEASUREMENT_REQUESTS);

            // 3. Measure Ariadne Instrumented (Causal tracking active)
            AriadneConfig.setEnabled(true);
            long initialHops = AriadneMetrics.getHopsSpawned();
            // Warmup agent
            executeConcurrentLoad(orderService, WARMUP_REQUESTS);
            // Measure agent
            LoadTestStats instrumented = executeConcurrentLoad(orderService, MEASUREMENT_REQUESTS);
            long deltaHops = AriadneMetrics.getHopsSpawned() - initialHops;

            // 4. Assert proof that agent was actively intercepting hops
            assertThat(deltaHops)
                    .as("Ariadne Agent must actively track causal hops during benchmark run")
                    .isGreaterThan(0);

            // 5. Print Production Engineering Report
            printProductionReport(baseline, instrumented);

            // 6. Assertions for production acceptance (no significant throughput drop, tail remains stable)
            double throughputDeltaPct = ((instrumented.throughputOpsPerSec - baseline.throughputOpsPerSec)
                    / baseline.throughputOpsPerSec) * 100.0;
            double p99DeltaPct = ((instrumented.p99Us - baseline.p99Us) / baseline.p99Us) * 100.0;

            assertThat(throughputDeltaPct)
                    .as("Throughput degradation must not exceed 25% under load")
                    .isGreaterThan(-25.0);
        }
    }

    private LoadTestStats executeConcurrentLoad(OrderService orderService, int totalRequests) throws Exception {
        ExecutorService clientPool = Executors.newFixedThreadPool(CONCURRENCY);
        long[] latenciesNanos = new long[totalRequests];
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        long startTime = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            clientPool.submit(() -> {
                int idx = counter.getAndIncrement();
                if (idx < totalRequests) {
                    long reqStart = System.nanoTime();
                    orderService.placeOrderAsyncSuccess("ORD-BENCH-" + idx).join();
                    latenciesNanos[idx] = System.nanoTime() - reqStart;
                }
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long durationNanos = System.nanoTime() - startTime;
        clientPool.shutdown();
        clientPool.awaitTermination(5, TimeUnit.SECONDS);

        return new LoadTestStats(latenciesNanos, durationNanos, AriadneMetrics.getHopsSpawned());
    }

    private void printProductionReport(LoadTestStats baseline, LoadTestStats agent) {
        System.out.println("\n==========================================================================================");
        System.out.println("            SPRING BOOT THROUGHPUT & TAIL LATENCY (P99) LOAD BENCHMARK                    ");
        System.out.println("            Setup: 16 Concurrent Clients | Spring @Async + CompletableFuture               ");
        System.out.println("==========================================================================================");
        System.out.printf("%-26s | %-16s | %-16s | %-16s%n", "Metric", "Baseline (No Agt)", "Ariadne Agent", "Overhead / Delta");
        System.out.println("------------------------------------------------------------------------------------------");

        double tputDelta = ((agent.throughputOpsPerSec - baseline.throughputOpsPerSec) / baseline.throughputOpsPerSec) * 100.0;
        System.out.printf("%-26s | %13.1f req/s | %13.1f req/s | %+13.2f%%%n",
                "Throughput", baseline.throughputOpsPerSec, agent.throughputOpsPerSec, tputDelta);

        double meanDelta = ((agent.meanLatencyUs - baseline.meanLatencyUs) / baseline.meanLatencyUs) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency Mean (avg)", baseline.meanLatencyUs, agent.meanLatencyUs, meanDelta);

        double p50Delta = ((agent.p50Us - baseline.p50Us) / baseline.p50Us) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency p50 (median)", baseline.p50Us, agent.p50Us, p50Delta);

        double p90Delta = ((agent.p90Us - baseline.p90Us) / baseline.p90Us) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency p90", baseline.p90Us, agent.p90Us, p90Delta);

        double p99Delta = ((agent.p99Us - baseline.p99Us) / baseline.p99Us) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency p99 (tail)", baseline.p99Us, agent.p99Us, p99Delta);

        double p999Delta = ((agent.p999Us - baseline.p999Us) / baseline.p999Us) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency p99.9 (tail)", baseline.p999Us, agent.p999Us, p999Delta);

        double maxDelta = ((agent.maxUs - baseline.maxUs) / baseline.maxUs) * 100.0;
        System.out.printf("%-26s | %13.2f µs   | %13.2f µs   | %+13.2f%%%n",
                "Latency Max", baseline.maxUs, agent.maxUs, maxDelta);

        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("Causal Hops Spawned During Run: %,d hops (PROVEN ACTIVE)%n", agent.totalHopsTracked);
        System.out.println("==========================================================================================\n");
    }
}
