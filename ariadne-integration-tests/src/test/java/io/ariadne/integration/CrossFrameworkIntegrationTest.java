package io.ariadne.integration;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.concurrent.Callable;
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

import io.ariadne.adapter.reactor.AriadneReactorAdapter;
import io.ariadne.adapter.rxjava.AriadneRxJavaAdapter;
import io.ariadne.agent.AriadneAgent;
import net.bytebuddy.agent.ByteBuddyAgent;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class CrossFrameworkIntegrationTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private static Class<?> BOOTSTRAP_CONTEXT;
    private static Class<?> BOOTSTRAP_LINK;

    @BeforeAll
    static void initAll() throws Exception {
        System.setProperty("net.bytebuddy.experimental", "true");
        Instrumentation inst = ByteBuddyAgent.install();
        AriadneAgent.install(inst);
        AriadneReactorAdapter.install();
        AriadneRxJavaAdapter.install();

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
    void shouldPropagateCausalityAcrossReactorToCompletableFutureToThreadPool() throws Exception {
        setContext(5001);

        ExecutorService dedicatedPool = Executors.newFixedThreadPool(2);
        try {
            // Reactor pipeline on boundedElastic -> calls CompletableFuture on commonPool -> calls custom ThreadPool
            Mono<String> pipeline = Mono.just("request-payload")
                    .publishOn(Schedulers.boundedElastic())
                    .map(payload -> {
                        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                Callable<String> queryTask = () -> {
                                    throw new IllegalStateException("Database query timeout in dedicated pool");
                                };
                                return dedicatedPool.submit(queryTask).get();
                            } catch (ExecutionException e) {
                                throw (RuntimeException) e.getCause();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
                        return future.join();
                    });

            assertThatThrownBy(() -> pipeline.block(Duration.ofSeconds(5)))
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(ex -> {
                        Throwable root = ex;
                        while (root.getCause() != null && root.getCause() != root) {
                            root = root.getCause();
                        }
                        assertThat(root).isInstanceOf(IllegalStateException.class);
                        assertThat(root.getSuppressed())
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                        && t.getMessage().contains("hops"));
                    });
        } finally {
            dedicatedPool.shutdownNow();
        }
    }

    @Test
    void shouldPropagateCausalityAcrossRxJavaToCompletableFuture() throws Exception {
        setContext(5002);

        io.reactivex.rxjava3.core.Single<String> stream = io.reactivex.rxjava3.core.Single.just("event")
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.computation())
                .map(item -> {
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> item + "-enriched")
                            .thenApplyAsync((String enriched) -> {
                                throw new IllegalArgumentException("Processing failed in CompletableFuture continuation");
                            });
                    return future.join();
                });

        assertThatThrownBy(stream::blockingGet)
                .isInstanceOf(RuntimeException.class)
                .satisfies(ex -> {
                    Throwable root = ex;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(IllegalArgumentException.class);
                    assertThat(root.getSuppressed())
                            .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                    && t.getMessage().contains("hops"));
                });
    }

    @Test
    void shouldPropagateCausalityWithVirtualThreadsAndReactor() throws Exception {
        setContext(5003);

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> task = virtualExecutor.submit(() -> {
                Mono<String> mono = Mono.just("virtual-input")
                        .publishOn(Schedulers.parallel())
                        .map((String val) -> {
                            throw new IllegalStateException("Error inside Reactor parallel scheduler spawned by Virtual Thread");
                        });
                return mono.block(Duration.ofSeconds(5));
            });

            assertThatThrownBy(task::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause();
                        assertThat(cause.getSuppressed())
                                .anyMatch(t -> t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")
                                        && t.getMessage().contains("hops"));
                    });
        }
    }

    @Test
    void shouldCapCausalityReconstructionAtMaxDepth() throws Exception {
        setContext(5004);

        // Chain 40 asynchronous hops sequentially
        CompletableFuture<Integer> future = CompletableFuture.completedFuture(0);
        for (int i = 0; i < 40; i++) {
            future = future.thenApplyAsync(val -> val + 1);
        }

        future = future.thenApplyAsync(val -> {
            throw new RuntimeException("Crash after 41 async hops");
        });

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    Throwable[] suppressed = cause.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> {
                                if (!t.getClass().getName().equals("io.ariadne.core.AsyncCausalityException")) {
                                    return false;
                                }
                                // Default max depth is 32, so elements should be bounded by 32
                                return t.getStackTrace().length <= 32;
                            });
                });
    }
}
