package io.ariadne.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void shouldNeverCreateNestedWrappersOnRepeatedWrapping() {
        Runnable rawRunnable = () -> {};
        Link link1 = AriadneContext.spawn(101);
        Link link2 = AriadneContext.spawn(102);

        Runnable wrapped1 = AriadneRunnable.wrap(rawRunnable, link1);
        assertThat(wrapped1).isInstanceOf(AriadneRunnable.class);
        assertThat(((AriadneRunnable) wrapped1).unwrap()).isSameAs(rawRunnable);

        // Wrapping with identical link should return same instance
        Runnable wrappedSame = AriadneRunnable.wrap(wrapped1, link1);
        assertThat(wrappedSame).isSameAs(wrapped1);

        // Wrapping with different link must NOT nest AriadneRunnable inside AriadneRunnable
        Runnable wrappedNewLink = AriadneRunnable.wrap(wrapped1, link2);
        assertThat(wrappedNewLink).isInstanceOf(AriadneRunnable.class);
        assertThat(((AriadneRunnable) wrappedNewLink).unwrap()).isSameAs(rawRunnable);
        assertThat(((AriadneRunnable) wrappedNewLink).capturedLink()).isSameAs(link2);

        // Factory without explicit link
        Runnable wrappedNoExplicitLink = AriadneRunnable.wrap(wrappedNewLink);
        assertThat(wrappedNoExplicitLink).isSameAs(wrappedNewLink);

        // Same test for AriadneCallable
        Callable<Integer> rawCallable = () -> 42;
        Callable<Integer> callableWrapped1 = AriadneCallable.wrap(rawCallable, link1);
        assertThat(((AriadneCallable<Integer>) callableWrapped1).unwrap()).isSameAs(rawCallable);

        Callable<Integer> callableWrapped2 = AriadneCallable.wrap(callableWrapped1, link2);
        assertThat(((AriadneCallable<Integer>) callableWrapped2).unwrap()).isSameAs(rawCallable);
        assertThat(((AriadneCallable<Integer>) callableWrapped2).capturedLink()).isSameAs(link2);

        Callable<Integer> callableWrappedSame = AriadneCallable.wrap(callableWrapped1, link1);
        assertThat(callableWrappedSame).isSameAs(callableWrapped1);
    }

    @Test
    void shouldPropagateCausalityAcrossThreadOfVirtual() throws Exception {
        int siteId = SiteRegistry.register(new CallSiteMetadata("VirtualApp", "spawnVirtual", "VirtualApp.java", 55));
        Link parent = AriadneContext.spawn(siteId);

        AtomicReference<Link> capturedInsideVThread = new AtomicReference<>();
        AtomicReference<Boolean> isVirtualThread = new AtomicReference<>(false);

        Runnable task = AriadneRunnable.wrap(() -> {
            isVirtualThread.set(Thread.currentThread().isVirtual());
            capturedInsideVThread.set(AriadneContext.current());
        }, parent);

        Thread vThread = Thread.ofVirtual().name("ariadne-vthread-test").start(task);
        vThread.join(5000);

        assertThat(isVirtualThread.get()).isTrue();
        assertThat(capturedInsideVThread.get()).isNotNull();
        assertThat(capturedInsideVThread.get().siteId).isEqualTo(siteId);
    }

    @Test
    void shouldEnrichExceptionThrownInsideThreadOfVirtual() throws Exception {
        int siteId = SiteRegistry.register(new CallSiteMetadata("VirtualService", "execute", "VirtualService.java", 77));
        Link parent = AriadneContext.spawn(siteId);

        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Runnable task = AriadneRunnable.wrap(() -> {
            throw new IllegalStateException("Crash in virtual thread worker");
        }, parent);

        Thread vThread = Thread.ofVirtual()
                .name("ariadne-vthread-error-test")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(task);
        vThread.join(5000);

        assertThat(uncaught.get())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed).hasSize(1);
                    assertThat(suppressed[0]).isInstanceOf(AsyncCausalityException.class);
                    StackTraceElement[] trace = suppressed[0].getStackTrace();
                    assertThat(trace[0].getClassName()).isEqualTo("VirtualService");
                    assertThat(trace[0].getMethodName()).isEqualTo("execute");
                    assertThat(trace[0].getLineNumber()).isEqualTo(77);
                });
    }
}
