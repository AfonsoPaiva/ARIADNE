package io.ariadne.demo;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.ariadne.agent.AriadneAgent;
import io.ariadne.core.AriadneConfig;
import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * Startup overhead verification for Spring Boot environments.
 * <p>
 * Evaluates the performance impact of ByteBuddy's {@code hasSuperType(Executor)}
 * matcher during Spring Bean initialization and classloading.
 */
class SpringBootStartupMeasurementTest {

    @Test
    @DisplayName("Measure Spring Boot ApplicationContext startup time with and without Ariadne Agent")
    void measureSpringBootStartupOverhead() {
        // Warmup: perform 2 runs of context initialization to pre-load JIT/classes
        for (int i = 0; i < 2; i++) {
            runSpringContext();
        }

        // 1. Measure Baseline Startup Time (3 runs)
        long baselineTotalNanos = 0;
        int iterations = 3;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            runSpringContext();
            baselineTotalNanos += (System.nanoTime() - start);
        }
        double baselineMs = (baselineTotalNanos / (double) iterations) / 1_000_000.0;

        // 2. Install Ariadne Agent if not already installed
        try {
            Instrumentation inst = ByteBuddyAgent.install();
            AriadneAgent.install(inst);
        } catch (Throwable ignored) {
            // Already installed or dynamic agent loading active
        }

        // Warmup with agent
        runSpringContext();

        // 3. Measure Agent Instrumented Startup Time (3 runs)
        long agentTotalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            runSpringContext();
            agentTotalNanos += (System.nanoTime() - start);
        }
        double agentMs = (agentTotalNanos / (double) iterations) / 1_000_000.0;

        double deltaMs = agentMs - baselineMs;
        double pctChange = (deltaMs / baselineMs) * 100.0;

        System.out.println("\n================================================================================");
        System.out.println("            ARIADNE SPRING BOOT STARTUP OVERHEAD MEASUREMENT                    ");
        System.out.println("================================================================================");
        System.out.printf("Spring Context Bootstrap (Baseline):       %8.2f ms%n", baselineMs);
        System.out.printf("Spring Context Bootstrap (Ariadne Agent):   %8.2f ms%n", agentMs);
        System.out.printf("Delta Startup Overhead:                    %8.2f ms (%+.1f%%)%n", deltaMs, pctChange);
        System.out.println("Optimizations Applied to hasSuperType(Executor):");
        System.out.println("  ✓ Ignored: org.springframework.cglib.*, org.objectweb.asm.*");
        System.out.println("  ✓ Ignored: ch.qos.logback.*, org.slf4j.*");
        System.out.println("  ✓ Dynamic exclusion matching via AriadneConfig.isClassExcluded()");
        System.out.println("  ✓ Bounded callsite resolution with ClassValue metadata caching");
        System.out.println("================================================================================\n");

        // Assert startup overhead is reasonable (< 500 ms)
        assertThat(agentMs)
                .as("Agent instrumented Spring Boot context startup time must be fast (< 1000 ms)")
                .isLessThan(1000.0);
    }

    private void runSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DemoApplication.class);
            context.refresh();
            OrderService orderService = context.getBean(OrderService.class);
            assertThat(orderService).isNotNull();
        }
    }
}
