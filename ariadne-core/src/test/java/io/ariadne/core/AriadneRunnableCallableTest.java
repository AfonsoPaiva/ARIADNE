package io.ariadne.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AriadneRunnableCallableTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        SiteRegistry.resetForTests();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        AriadneContext.clear();
    }

    @Test
    void runnableShouldPropagateContextAndCleanUpOnSuccess() throws Exception {
        int siteId = SiteRegistry.register(new CallSiteMetadata("App", "dispatch", "App.java", 12));
        Link parent = AriadneContext.spawn(siteId);

        Runnable task = AriadneRunnable.wrap(() -> {
            Link insideThread = AriadneContext.current();
            assertThat(insideThread).isNotNull();
            assertThat(insideThread.siteId).isEqualTo(siteId);
        }, parent);

        Future<?> future = executor.submit(task);
        future.get(2, TimeUnit.SECONDS);

        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void runnableShouldEnrichExceptionWhenTaskFails() {
        int siteId = SiteRegistry.register(new CallSiteMetadata("App", "dispatch", "App.java", 12));
        Link parent = AriadneContext.spawn(siteId);

        Runnable task = AriadneRunnable.wrap(() -> {
            throw new IllegalStateException("Simulated crash in worker thread");
        }, parent);

        assertThatThrownBy(task::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated crash in worker thread")
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed).hasSize(1);
                    assertThat(suppressed[0]).isInstanceOf(AsyncCausalityException.class);
                    StackTraceElement[] trace = suppressed[0].getStackTrace();
                    assertThat(trace[0].getClassName()).isEqualTo("App");
                    assertThat(trace[0].getMethodName()).isEqualTo("dispatch");
                    assertThat(trace[0].getLineNumber()).isEqualTo(12);
                });
    }

    @Test
    void callableShouldPropagateContextAndEnrichException() {
        int siteId = SiteRegistry.register(new CallSiteMetadata("Service", "call", "Service.java", 99));
        Link parent = AriadneContext.spawn(siteId);

        Callable<String> task = AriadneCallable.wrap(() -> {
            throw new RuntimeException("Callable failed");
        }, parent);

        assertThatThrownBy(task::call)
                .isInstanceOf(RuntimeException.class)
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed).hasSize(1);
                    assertThat(suppressed[0]).isInstanceOf(AsyncCausalityException.class);
                });
    }
}
