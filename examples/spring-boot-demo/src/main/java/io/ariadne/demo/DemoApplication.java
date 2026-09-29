package io.ariadne.demo;

import io.ariadne.adapter.mdc.AriadneMdcAdapter;
import io.ariadne.adapter.reactor.AriadneReactorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Interactive Demo comparing exception stack traces:
 * 1. Standard JVM (Truncated stack trace — blindness at thread boundaries)
 * 2. Ariadne Attached (Reconstructed causality via suppressed AsyncCausalityException)
 */
@Configuration
@EnableAsync
public class DemoApplication {

    private static final Logger log = LoggerFactory.getLogger(DemoApplication.class);

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("spring-async-");
        executor.initialize();
        return executor;
    }

    @Bean
    public PaymentService paymentService() {
        return new PaymentService();
    }

    @Bean
    public OrderService orderService(PaymentService paymentService) {
        return new OrderService(paymentService);
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("               ARIADNE SPRING BOOT DEMO — BEFORE VS AFTER                       ");
        System.out.println("================================================================================\n");

        // Install Ariadne adapters for reactive and MDC context
        AriadneReactorAdapter.install();
        AriadneMdcAdapter.install();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DemoApplication.class);
            context.refresh();

            OrderService orderService = context.getBean(OrderService.class);

            // Set up MDC correlation
            MDC.put("traceId", "req-89a1f4b2");
            MDC.put("userId", "afonso-paiva");

            System.out.println(">>> SCENARIO 1: Spring @Async + CompletableFuture Multi-Hop Exception");
            System.out.println("OrderController calls OrderService.placeOrderAsync() -> PaymentService.processPayment()\n");

            try {
                orderService.placeOrderAsync("ORD-2026-999").join();
            } catch (Throwable t) {
                printExceptionDetails("Spring @Async Flow", t);
            }

            System.out.println("\n--------------------------------------------------------------------------------\n");

            System.out.println(">>> SCENARIO 2: Project Reactor / WebFlux Scheduler Exception");
            System.out.println("Pipeline: publishOn(boundedElastic) -> map() -> publishOn(parallel) -> error\n");

            try {
                orderService.processOrderReactive("ORD-2026-888").block();
            } catch (Throwable t) {
                printExceptionDetails("Reactor / WebFlux Flow", t);
            }
        } finally {
            MDC.clear();
        }

        System.out.println("\n================================================================================");
        System.out.println("                             DEMO COMPLETE                                      ");
        System.out.println("================================================================================");
    }

    private static void printExceptionDetails(String scenario, Throwable t) {
        System.out.println("Captured Exception for [" + scenario + "]:");
        System.out.println("Root Cause: " + t.getClass().getName() + ": " + t.getMessage());

        Throwable[] suppressed = t.getSuppressed();
        if (suppressed.length == 0 && t.getCause() != null) {
            suppressed = t.getCause().getSuppressed();
        }

        boolean foundAriadne = false;
        for (Throwable s : suppressed) {
            if (s.getClass().getName().contains("AsyncCausalityException")) {
                foundAriadne = true;
                System.out.println("\n[ARIADNE RECONSTRUCTION DETECTED]:");
                System.out.println("  " + s.getMessage());
                for (StackTraceElement elem : s.getStackTrace()) {
                    System.out.println("    at " + elem);
                }
            }
        }

        if (!foundAriadne) {
            System.out.println("\n[WITHOUT ARIADNE]:");
            System.out.println("  Notice how the caller (OrderService.placeOrderAsync) is completely MISSING");
            System.out.println("  from the JVM stack trace above. Only the background worker thread is visible.");
        }
    }
}
