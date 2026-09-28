package io.ariadne.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bytebuddy.agent.ByteBuddyAgent;

class AriadneAgentTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private static Class<?> BOOTSTRAP_CONTEXT;
    private static Class<?> BOOTSTRAP_LINK;

    @BeforeAll
    static void initAgent() throws Exception {
        System.setProperty("net.bytebuddy.experimental", "true");
        Instrumentation inst = ByteBuddyAgent.install();
        AriadneAgent.install(inst);

        BOOTSTRAP_CONTEXT = Class.forName("io.ariadne.core.AriadneContext", true, null);
        BOOTSTRAP_LINK = Class.forName("io.ariadne.core.Link", true, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        clearContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearContext();
    }

    private static void setContext(int siteId) throws Exception {
        Object link = BOOTSTRAP_CONTEXT.getMethod("spawn", int.class).invoke(null, siteId);
        BOOTSTRAP_CONTEXT.getMethod("set", BOOTSTRAP_LINK).invoke(null, link);
    }

    private static void clearContext() throws Exception {
        if (BOOTSTRAP_CONTEXT != null) {
            BOOTSTRAP_CONTEXT.getMethod("clear").invoke(null);
        }
    }

    @Test
    void shouldCaptureCausalityInCompletableFutureCommonPool() throws Exception {
        setContext(1001);

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("Crash inside ForkJoinPool.commonPool");
        });

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    Throwable[] suppressed = cause.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                    && t.getMessage().contains("hops"));
                });
    }

    @Test
    void shouldCaptureMultiHopContinuationsInCompletableFuture() throws Exception {
        setContext(1002);

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> "hello")
                .thenApplyAsync(s -> {
                    throw new RuntimeException("Crash inside thenApplyAsync hop");
                });

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    Throwable[] suppressed = cause.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                    && t.getMessage().contains("2 hops"));
                });
    }

    @Test
    void shouldCaptureCausalityInCompletableFutureWithVirtualThreads() throws Exception {
        setContext(1003);

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                throw new RuntimeException("Crash inside virtual thread");
            }, virtualExecutor);

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        Throwable[] suppressed = cause.getSuppressed();
                        assertThat(suppressed)
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                        && t.getMessage().contains("hops"));
                    });
        }
    }

    @Test
    void shouldCaptureCausalityInExecutorServiceSubmit() throws Exception {
        setContext(1004);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> future = pool.submit(() -> {
                throw new IllegalStateException("Callable failed in thread pool");
            });

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        Throwable[] suppressed = cause.getSuppressed();
                        assertThat(suppressed)
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                        && t.getMessage().contains("hops"));
                    });
        } finally {
            pool.shutdownNow();
        }
    }
}
