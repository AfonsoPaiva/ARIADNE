package io.ariadne.integration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.MDC;

/**
 * Sample console application invoked in a separate forked JVM process with
 * {@code -javaagent} attached to verify real-world production causality propagation.
 */
public class ForkedAgentSampleApp {

    public static void main(String[] args) {
        MDC.put("traceId", "forked-trace-999");
        MDC.put("tenant", "acme-corp");

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                // Hop 1 on pool worker
                return CompletableFuture.<String>supplyAsync(() -> {
                    // Hop 2 spawned from Hop 1
                    throw new IllegalStateException("Simulated crash in forked JVM async task: hop-1-data");
                }, pool).join();
            }, pool);

            try {
                future.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                System.out.println("ROOT_CAUSE=" + cause.getClass().getName() + ": " + cause.getMessage());
                for (Throwable suppressed : cause.getSuppressed()) {
                    System.out.println("SUPPRESSED_CLASS=" + suppressed.getClass().getName());
                    System.out.println("CAUSAL_EXCEPTION_FOUND=" + suppressed.getMessage());
                    for (StackTraceElement elem : suppressed.getStackTrace()) {
                        System.out.println("CAUSAL_FRAME=" + elem);
                    }
                }
            }
        }

        // 2. Direct Virtual Thread execution test
        java.util.concurrent.atomic.AtomicReference<Throwable> uncaughtEx = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch vLatch = new java.util.concurrent.CountDownLatch(1);
        Thread vt = Thread.ofVirtual().name("ariadne-vt-test").unstarted(() -> {
            throw new IllegalStateException("Simulated crash in direct virtual thread");
        });
        vt.setUncaughtExceptionHandler((t, e) -> {
            uncaughtEx.set(e);
            vLatch.countDown();
        });
        vt.start();
        try {
            if (vLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Throwable t = uncaughtEx.get();
                if (t != null) {
                    for (Throwable suppressed : t.getSuppressed()) {
                        if (suppressed.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")) {
                            System.out.println("DIRECT_VTHREAD_CAUSAL_EXCEPTION=" + suppressed.getMessage());
                            for (StackTraceElement elem : suppressed.getStackTrace()) {
                                System.out.println("VTHREAD_CAUSAL_FRAME=" + elem);
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
