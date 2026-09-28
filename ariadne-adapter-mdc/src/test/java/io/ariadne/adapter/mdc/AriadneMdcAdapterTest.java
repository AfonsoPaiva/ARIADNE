package io.ariadne.adapter.mdc;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneMetrics;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.Link;
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
        MDC.clear();
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
        AriadneMdcAdapter.install();
    }

    @AfterEach
    void tearDown() {
        AriadneMdcAdapter.uninstall();
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
}
