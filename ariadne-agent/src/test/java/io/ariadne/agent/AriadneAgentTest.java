package io.ariadne.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

        Runnable rawTask = () -> {
            throw new IllegalStateException("Crash in Thread.ofVirtual");
        };

        // Note: rawTask is passed directly WITHOUT manual AriadneRunnable.wrap!
        // The ByteBuddy agent's ThreadAdvice intercepts Thread.ofVirtual().start() automatically.
        Thread vThread = Thread.ofVirtual()
                .name("direct-virtual-test")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(rawTask);
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
    void shouldPropagateCausalityAcrossThreadStartVirtualThread() throws Exception {
        setContext(2003);

        java.util.concurrent.atomic.AtomicReference<Throwable> uncaught = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable rawTask = () -> {
            throw new IllegalStateException("Crash in Thread.startVirtualThread");
        };

        Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.set(e));
            Thread vThread = Thread.startVirtualThread(rawTask);
            vThread.join(5000);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(oldHandler);
        }

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
    void shouldHandleNestedSubmitsAndCallerRunsPolicyWithoutDoubleWrapping() throws Exception {
        Class<?> metricsClass = Class.forName("io.ariadne.core.AriadneMetrics", true, null);
        metricsClass.getMethod("reset").invoke(null);

        // Pool with 1 thread, 1-element queue, and CallerRunsPolicy
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        try {
            CountDownLatch task1Started = new CountDownLatch(1);
            CountDownLatch task1Release = new CountDownLatch(1);

            // Task 1 occupies the single worker thread
            pool.submit(() -> {
                task1Started.countDown();
                task1Release.await();
                return "task1";
            });
            task1Started.await();

            // Task 2 occupies the queue capacity
            pool.submit(() -> "task2-in-queue");

            // Task 3 triggers CallerRunsPolicy and executes synchronously on the calling thread.
            // Inside Task 3, another submit() is issued to test nested SUBMIT_DEPTH tracking!
            Future<String> task3Future = pool.submit(() -> {
                Future<String> nested = pool.submit(() -> "nested-result");
                return "task3-" + nested.get();
            });

            task1Release.countDown();
            assertThat(task3Future.get()).isEqualTo("task3-nested-result");

            long hops = (long) metricsClass.getMethod("getHopsSpawned").invoke(null);
            // Exactly 4 submit calls were issued (task1, task2, task3, nested)
            // If SUBMIT_DEPTH was broken or double-wrapped via execute(), hops would exceed 4
            assertThat(hops).as("Each submit must spawn exactly 1 causal hop even with nested CallerRunsPolicy").isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldPropagateCausalityInScheduledExecutorService() throws Exception {
        setContext(2004);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Future<String> future = scheduler.schedule(() -> {
                throw new IllegalStateException("Crash in scheduled task");
            }, 10, TimeUnit.MILLISECONDS);

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        Throwable[] suppressed = cause.getSuppressed();
                        assertThat(suppressed)
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                        && t.getMessage().contains("hop"));
                    });
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldPropagateCausalityInCompletableFutureDelayedExecutor() throws Exception {
        setContext(2005);

        Executor delayed = CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("Crash in delayedExecutor");
        }, delayed);

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    Throwable[] suppressed = cause.getSuppressed();
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

    @Test
    void shouldBypassTrackingWhenKillSwitchActivated() throws Exception {
        Class<?> configClass = Class.forName("io.ariadne.core.AriadneConfig", true, null);
        configClass.getMethod("setEnabled", boolean.class).invoke(null, false);
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("Crash with kill switch active");
            });

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        Throwable[] suppressed = cause.getSuppressed();
                        assertThat(suppressed)
                                .as("When kill switch is enabled, no AsyncCausalityException should be attached")
                                .noneMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"));
                    });
        } finally {
            configClass.getMethod("resetDefaults").invoke(null);
        }
    }

    static class ExcludedWorkerTask implements Runnable {
        @Override
        public void run() {
            throw new IllegalStateException("Crash in excluded worker task");
        }
    }

    @Test
    void shouldBypassExcludedClasses() throws Exception {
        Class<?> configClass = Class.forName("io.ariadne.core.AriadneConfig", true, null);
        java.util.List<String> excludes = java.util.List.of("io.ariadne.agent.AriadneAgentTest$ExcludedWorkerTask");
        configClass.getMethod("setExcludes", java.util.List.class).invoke(null, excludes);
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = executor.submit(new ExcludedWorkerTask());
                assertThatThrownBy(future::get)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class)
                        .satisfies(ex -> {
                            Throwable cause = ex.getCause();
                            Throwable[] suppressed = cause.getSuppressed();
                            assertThat(suppressed)
                                    .as("When task class is excluded, no AsyncCausalityException should be attached")
                                    .noneMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException"));
                        });
            } finally {
                executor.shutdown();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } finally {
            configClass.getMethod("resetDefaults").invoke(null);
        }
    }
}
