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

    @Test
    void shouldNotDoubleWrapWhenExecutorServiceSubmitDelegatesToExecute() throws Exception {
        Class<?> metricsClass = Class.forName("io.ariadne.core.AriadneMetrics", true, null);
        metricsClass.getMethod("reset").invoke(null);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            // submit(Callable) -> AbstractExecutorService delegates to execute(FutureTask)
            Future<String> callableFuture = pool.submit(() -> "success-callable");
            assertThat(callableFuture.get()).isEqualTo("success-callable");

            long hopsAfterCallable = (long) metricsClass.getMethod("getHopsSpawned").invoke(null);
            // Exactly 1 hop should be recorded for submit(Callable), NOT 2 from execute(FutureTask)
            assertThat(hopsAfterCallable).as("submit(Callable) must not double-wrap on execute").isEqualTo(1);

            // submit(Runnable) -> AbstractExecutorService delegates to execute(FutureTask)
            Future<?> runnableFuture = pool.submit(() -> {});
            runnableFuture.get();

            long hopsAfterRunnable = (long) metricsClass.getMethod("getHopsSpawned").invoke(null);
            // Exactly 1 additional hop for submit(Runnable)
            assertThat(hopsAfterRunnable).as("submit(Runnable) must not double-wrap on execute").isEqualTo(2);

            // Direct execute(Runnable)
            pool.execute(() -> {});
            Thread.sleep(50);

            long hopsAfterExecute = (long) metricsClass.getMethod("getHopsSpawned").invoke(null);
            assertThat(hopsAfterExecute).as("direct execute(Runnable) must record exactly 1 hop").isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldPropagateCausalityAcrossVirtualThreadPerTaskExecutor() throws Exception {
        setContext(2001);

        try (ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = virtualPool.submit(() -> {
                throw new IllegalStateException("Crash in Virtual Thread Task");
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
        }
    }

    @Test
    void shouldPropagateCausalityAcrossDirectThreadOfVirtual() throws Exception {
        setContext(2002);

        java.util.concurrent.atomic.AtomicReference<Throwable> uncaught = new java.util.concurrent.atomic.AtomicReference<>();
        Class<?> runnableClass = Class.forName("io.ariadne.core.AriadneRunnable", true, null);
        Object currentLink = BOOTSTRAP_CONTEXT.getMethod("current").invoke(null);

        Runnable rawTask = () -> {
            throw new IllegalStateException("Crash in Thread.ofVirtual");
        };

        // Wrap using AriadneRunnable loaded in bootstrap
        Runnable wrappedTask = (Runnable) runnableClass.getMethod("wrap", Runnable.class, BOOTSTRAP_LINK)
                .invoke(null, rawTask, currentLink);

        Thread vThread = Thread.ofVirtual()
                .name("direct-virtual-test")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(wrappedTask);
        vThread.join(5000);

        assertThat(uncaught.get())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                    && t.getMessage().contains("hop"));
                });
    }

    @Test
    void shouldCaptureCallerTestFrameInAsyncCausalityExceptionInClassMode() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("Simulated crash in background task (class mode)");
        });

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    Throwable[] suppressed = cause.getSuppressed();
                    assertThat(suppressed)
                            .as("Suppressed exceptions must contain AsyncCausalityException")
                            .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"));

                    Throwable causalityException = java.util.Arrays.stream(suppressed)
                            .filter(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"))
                            .findFirst()
                            .orElseThrow();

                    StackTraceElement[] frames = causalityException.getStackTrace();
                    String testClassName = AriadneAgentTest.class.getName();
                    boolean hasCallerFrame = java.util.Arrays.stream(frames)
                            .anyMatch(f -> f.getClassName().equals(testClassName) || f.getClassName().startsWith(testClassName + "$"));

                    assertThat(hasCallerFrame)
                            .as("Reconstructed AsyncCausalityException must contain caller test frame (%s), but got frames: %s",
                                    testClassName, java.util.Arrays.toString(frames))
                            .isTrue();
                });
    }

    @Test
    void shouldCaptureCallerTestFrameInAsyncCausalityExceptionInFullMode() throws Exception {
        Class<?> configClass = Class.forName("io.ariadne.core.AriadneConfig", true, null);
        configClass.getMethod("setCallSiteMode", String.class).invoke(null, "full");
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("Simulated crash in background task (full mode)");
            });

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        Throwable[] suppressed = cause.getSuppressed();
                        assertThat(suppressed)
                                .as("Suppressed exceptions must contain AsyncCausalityException")
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"));

                        Throwable causalityException = java.util.Arrays.stream(suppressed)
                                .filter(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"))
                                .findFirst()
                                .orElseThrow();

                        StackTraceElement[] frames = causalityException.getStackTrace();
                        String testClassName = AriadneAgentTest.class.getName();
                        StackTraceElement callerFrame = java.util.Arrays.stream(frames)
                                .filter(f -> f.getClassName().equals(testClassName))
                                .findFirst()
                                .orElse(null);

                        assertThat(callerFrame)
                                .as("Reconstructed AsyncCausalityException must contain caller frame %s, got: %s",
                                        testClassName, java.util.Arrays.toString(frames))
                                .isNotNull();

                        assertThat(callerFrame.getMethodName())
                                .isEqualTo("shouldCaptureCallerTestFrameInAsyncCausalityExceptionInFullMode");
                        assertThat(callerFrame.getLineNumber())
                                .isGreaterThan(0);
                    });
        } finally {
            configClass.getMethod("resetDefaults").invoke(null);
        }
    }
}
