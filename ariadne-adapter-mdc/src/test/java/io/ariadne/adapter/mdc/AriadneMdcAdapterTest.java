package io.ariadne.adapter.mdc;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneMetrics;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.ContextCarrier;
import io.ariadne.core.Link;
import io.ariadne.core.ThreadLocalContextCarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AriadneMdcAdapterTest {

    @BeforeEach
    void setUp() {
        AriadneMdcAdapter.uninstall();
        AriadneContext.setCarrier(new ThreadLocalContextCarrier());
        MDC.clear();
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
        AriadneMdcAdapter.install();
    }

    @AfterEach
    void tearDown() {
        AriadneMdcAdapter.uninstall();
        AriadneContext.setCarrier(new ThreadLocalContextCarrier());
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
        MDC.clear();
    }

    @Test
    void shouldInstallAndUninstallCorrectly() {
        assertThat(AriadneMdcAdapter.isInstalled()).isTrue();
        assertThat(AriadneContext.carrier()).isInstanceOf(AriadneMdcCarrier.class);

        AriadneMdcAdapter.uninstall();
        assertThat(AriadneMdcAdapter.isInstalled()).isFalse();
        assertThat(AriadneContext.carrier()).isNotInstanceOf(AriadneMdcCarrier.class);
    }

    @Test
    void shouldPropagateMdcAcrossAriadneContextScope() {
        MDC.put("traceId", "tx-999");
        MDC.put("userId", "alice");

        Link link = AriadneContext.spawn(101);
        assertThat(link.attachment).isNotNull();

        // Clear current thread's MDC to simulate clean worker thread
        MDC.clear();
        assertThat(MDC.get("traceId")).isNull();

        try (var ignored = AriadneContext.attach(link)) {
            assertThat(MDC.get("traceId")).isEqualTo("tx-999");
            assertThat(MDC.get("userId")).isEqualTo("alice");
        }

        // Scope closed: worker thread must be clean again
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void shouldPropagateMdcAcrossThreads() throws Exception {
        MDC.put("correlationId", "corr-42");

        Link link = AriadneContext.spawn(202);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> workerMdc = new AtomicReference<>();
            AtomicReference<String> workerMdcAfterScope = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            executor.submit(() -> {
                try (var ignored = AriadneContext.attach(link)) {
                    workerMdc.set(MDC.get("correlationId"));
                }
                workerMdcAfterScope.set(MDC.get("correlationId"));
                latch.countDown();
            });

            boolean completed = latch.await(3, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(workerMdc.get()).isEqualTo("corr-42");
            assertThat(workerMdcAfterScope.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRestoreExistingMdcInWorkerThread() throws Exception {
        MDC.put("requestId", "req-parent");
        Link link = AriadneContext.spawn(303);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> workerInitialMdc = new AtomicReference<>();
            AtomicReference<String> workerScopedMdc = new AtomicReference<>();
            AtomicReference<String> workerRestoredMdc = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            executor.submit(() -> {
                MDC.put("workerId", "worker-1");
                workerInitialMdc.set(MDC.get("workerId"));

                try (var ignored = AriadneContext.attach(link)) {
                    workerScopedMdc.set(MDC.get("requestId"));
                }

                workerRestoredMdc.set(MDC.get("workerId"));
                latch.countDown();
            });

            boolean completed = latch.await(3, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(workerInitialMdc.get()).isEqualTo("worker-1");
            assertThat(workerScopedMdc.get()).isEqualTo("req-parent");
            assertThat(workerRestoredMdc.get()).isEqualTo("worker-1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRespectMdcDisabledConfig() {
        AriadneConfig.setMdcPropagationEnabled(false);

        MDC.put("traceId", "should-not-propagate");
        Link link = AriadneContext.spawn(404);

        assertThat(link.attachment).isNull();

        MDC.clear();
        try (var ignored = AriadneContext.attach(link)) {
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    @Test
    void shouldWrapRunnableAndCallable() throws Exception {
        MDC.put("spanId", "span-123");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> runnableResult = new AtomicReference<>();
            Runnable wrappedRunnable = AriadneMdcAdapter.wrap(() -> {
                runnableResult.set(MDC.get("spanId"));
            });

            MDC.clear();
            executor.submit(wrappedRunnable).get(2, TimeUnit.SECONDS);
            assertThat(runnableResult.get()).isEqualTo("span-123");

            // Callable
            MDC.put("spanId", "span-456");
            Callable<String> wrappedCallable = AriadneMdcAdapter.wrap(() -> MDC.get("spanId"));
            MDC.clear();

            Future<String> future = executor.submit(wrappedCallable);
            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("span-456");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRecordCanaryProbeInMetrics() {
        CanaryProbeResult probe = AriadneMetrics.getCanaryResults().get("SLF4J MDC");
        assertThat(probe).isNotNull();
        assertThat(probe.isHealthy()).isTrue();
        assertThat(probe.framework()).isEqualTo("SLF4J MDC");
    }

    @Test
    void shouldHandleMdcAdapterEdgeCases() throws Exception {
        // Multiple install calls (idempotence)
        AriadneMdcAdapter.install();
        AriadneMdcAdapter.install();
        assertThat(AriadneMdcAdapter.isInstalled()).isTrue();

        // Multiple uninstall calls (idempotence)
        AriadneMdcAdapter.uninstall();
        AriadneMdcAdapter.uninstall();
        assertThat(AriadneMdcAdapter.isInstalled()).isFalse();

        // Reinstall
        AriadneMdcAdapter.install();

        // Null checks on wrap
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> AriadneMdcAdapter.wrap((Runnable) null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> AriadneMdcAdapter.wrap((Callable<?>) null));

        // Wrap when MDC is initially empty
        MDC.clear();
        Runnable emptyRunnable = AriadneMdcAdapter.wrap(() -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        emptyRunnable.run();

        Callable<String> emptyCallable = AriadneMdcAdapter.wrap(() -> {
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            return "ok";
        });
        assertThat(emptyCallable.call()).isEqualTo("ok");

        // Private constructor via reflection
        java.lang.reflect.Constructor<AriadneMdcAdapter> ctor = AriadneMdcAdapter.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }

    @Test
    void shouldExerciseAriadneMdcCarrierDirectly() {
        ContextCarrier delegate = AriadneContext.carrier();
        if (delegate instanceof AriadneMdcCarrier carrier) {
            delegate = carrier.getDelegate();
        }

        AriadneMdcCarrier carrier = new AriadneMdcCarrier(delegate);
        assertThat(carrier.getDelegate()).isSameAs(delegate);

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new AriadneMdcCarrier(null));

        Link dummyLink = new Link(null, 123, 1L, null);
        carrier.set(dummyLink);
        assertThat(carrier.current()).isEqualTo(dummyLink);
        carrier.clear();
        assertThat(carrier.current()).isNull();

        // spawn without attachment
        MDC.put("foo", "bar");
        Link spawned1 = carrier.spawn(456);
        assertThat(spawned1.attachment).isNotNull();

        // spawn with explicit non-null attachment (preserves custom payload)
        Link spawned2 = carrier.spawn(789, "customPayload");
        assertThat(spawned2.attachment).isEqualTo("customPayload");

        // attach with null link
        ContextCarrier.Scope nullScope = carrier.attach(null);
        assertThat(nullScope).isNotNull();
        nullScope.close();

        // attach with non-map attachment
        ContextCarrier.Scope nonMapScope = carrier.attach(spawned2);
        assertThat(nonMapScope).isNotNull();
        nonMapScope.close();

        // attach with MDC propagation disabled
        AriadneConfig.setMdcPropagationEnabled(false);
        ContextCarrier.Scope disabledScope = carrier.attach(spawned1);
        assertThat(disabledScope).isNotNull();
        disabledScope.close();
        AriadneConfig.setMdcPropagationEnabled(true);

        // attach when previous MDC was empty
        MDC.clear();
        ContextCarrier.Scope emptyPreScope = carrier.attach(spawned1);
        assertThat(MDC.get("foo")).isEqualTo("bar");
        emptyPreScope.close();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        // attach when previous MDC was NOT empty
        MDC.put("pre", "existing");
        ContextCarrier.Scope nonPreScope = carrier.attach(spawned1);
        assertThat(MDC.get("foo")).isEqualTo("bar");
        nonPreScope.close();
        assertThat(MDC.get("pre")).isEqualTo("existing");
        assertThat(MDC.get("foo")).isNull();
    }

    @Test
    void shouldExerciseBootstrapCarrierProxyAndSynchronization() throws Exception {
        // Synchronize with AriadneContext
        AriadneMdcAdapter.synchronizeBootstrapCarrier(AriadneContext.class);

        ContextCarrier proxyCarrier = AriadneContext.carrier();
        assertThat(proxyCarrier).isNotNull();

        // test proxy spawn with 1 arg
        MDC.put("bootKey", "bootVal");
        Link link1 = proxyCarrier.spawn(888);
        assertThat(link1.attachment).isNotNull();

        // test proxy spawn with 2 args (payload provided)
        Link link2 = proxyCarrier.spawn(889, "bootPayload");
        assertThat(link2.attachment).isEqualTo("bootPayload");

        // test delegate pass-through (e.g. current, clear, set)
        proxyCarrier.set(link1);
        assertThat(proxyCarrier.current()).isEqualTo(link1);
        proxyCarrier.clear();
        assertThat(proxyCarrier.current()).isNull();

        // test attach with null
        ContextCarrier.Scope nullScope = proxyCarrier.attach(null);
        nullScope.close();

        // test attach with non-map
        ContextCarrier.Scope nonMapScope = proxyCarrier.attach(link2);
        nonMapScope.close();

        // test attach with map when previous MDC was empty
        MDC.clear();
        ContextCarrier.Scope s1 = proxyCarrier.attach(link1);
        assertThat(MDC.get("bootKey")).isEqualTo("bootVal");
        // call scope toString (non-close method)
        assertThat(s1.toString()).isNotNull();
        s1.close();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        // test attach with map when previous MDC was not empty
        MDC.put("prior", "priorVal");
        ContextCarrier.Scope s2 = proxyCarrier.attach(link1);
        assertThat(MDC.get("bootKey")).isEqualTo("bootVal");
        s2.close();
        assertThat(MDC.get("prior")).isEqualTo("priorVal");
        assertThat(MDC.get("bootKey")).isNull();

        // test attach with MDC propagation disabled
        AriadneConfig.setMdcPropagationEnabled(false);
        ContextCarrier.Scope s3 = proxyCarrier.attach(link1);
        s3.close();
        AriadneConfig.setMdcPropagationEnabled(true);

        // restore bootstrap carrier
        AriadneMdcAdapter.restoreBootstrapCarrier(AriadneContext.class);
        AriadneMdcAdapter.restoreBootstrapCarrier(null);
    }

    @Test
    void shouldHandleCanaryProbeFailureAndFailFast() {
        // Canary probe when previous MDC is NOT empty
        MDC.put("preProbe", "val");
        CanaryProbeResult probeWithPre = AriadneMdcAdapter.runCanaryProbe();
        assertThat(probeWithPre.isHealthy()).isTrue();
        assertThat(MDC.get("preProbe")).isEqualTo("val");

        // Install a broken carrier that doesn't propagate MDC
        AriadneMdcAdapter.uninstall();
        ContextCarrier brokenCarrier = new ContextCarrier() {
            @Override public Link current() { return null; }
            @Override public void set(Link link) {}
            @Override public void clear() {}
            @Override public Link spawn(int siteId) { return spawn(siteId, null); }
            @Override public Link spawn(int siteId, Object attachment) { return new Link(null, siteId, 1L, null); }
            @Override public ContextCarrier.Scope attach(Link link) { return () -> {}; }
        };
        AriadneContext.setCarrier(brokenCarrier);

        CanaryProbeResult failedProbe = AriadneMdcAdapter.runCanaryProbe();
        assertThat(failedProbe.isHealthy()).isFalse();
        assertThat(failedProbe.message()).contains("MDC value was not preserved");

        // Fail-fast test
        AriadneConfig.setFailFast(true);
        AriadneConfig.setCanaryProbesEnabled(true);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, AriadneMdcAdapter::install);
        } finally {
            AriadneConfig.setFailFast(false);
            AriadneContext.setCarrier(new ThreadLocalContextCarrier());
            AriadneMdcAdapter.install();
        }
    }

    @Test
    void shouldHandleWrapExceptionsAndRestoration() {
        MDC.put("key", "val");
        Runnable throwingRunnable = AriadneMdcAdapter.wrap((Runnable) () -> {
            throw new RuntimeException("boom-runnable");
        });
        MDC.put("key", "outerVal");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, throwingRunnable::run);
        assertThat(MDC.get("key")).isEqualTo("outerVal");

        Callable<String> throwingCallable = AriadneMdcAdapter.wrap((Callable<String>) () -> {
            throw new RuntimeException("boom-callable");
        });
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, throwingCallable::call);
        assertThat(MDC.get("key")).isEqualTo("outerVal");
    }
}
