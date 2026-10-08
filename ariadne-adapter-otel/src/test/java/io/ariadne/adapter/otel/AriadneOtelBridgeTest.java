package io.ariadne.adapter.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.AsyncCausalityException;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.ariadne.core.TraceContext;
import io.ariadne.core.TraceContexts;
import io.opentelemetry.api.trace.StubOtel;

class AriadneOtelBridgeTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @BeforeEach
    void setUp() {
        StubOtel.reset();
        AriadneContext.clear();
    }

    @AfterEach
    void tearDown() {
        AriadneOtelBridge.uninstall();
        StubOtel.reset();
        AriadneContext.clear();
        AriadneConfig.resetDefaults();
    }

    @Test
    void installActivatesBridgeAndEnablesPropagation() {
        assertThat(AriadneOtelBridge.isInstalled()).isFalse();
        assertThat(AriadneOtelBridge.install()).isTrue();
        assertThat(AriadneOtelBridge.isInstalled()).isTrue();
        assertThat(TraceContexts.isEnabled()).isTrue();
        // idempotent
        assertThat(AriadneOtelBridge.install()).isTrue();
    }

    @Test
    void uninstallRestoresPreviousState() {
        AriadneOtelBridge.install();
        AriadneOtelBridge.uninstall();
        assertThat(AriadneOtelBridge.isInstalled()).isFalse();
        assertThat(TraceContexts.isEnabled()).isFalse();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 1));
        assertThat(TraceContexts.current()).isNull();
    }

    @Test
    void activeOtelSpanIsCapturedOnSpawn() {
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 1));

        Link link = AriadneContext.spawn(1);

        TraceContext tc = (TraceContext) link.attachment;
        assertThat(tc.traceId()).isEqualTo(TRACE_ID);
        assertThat(tc.spanId()).isEqualTo(SPAN_ID);
        assertThat(tc.isSampled()).isTrue();
        assertThat(AriadneOtelBridge.currentSpan()).isEqualTo(tc);
    }

    @Test
    void noActiveSpanMeansNoAttachment() {
        AriadneOtelBridge.install();
        assertThat(AriadneContext.spawn(1).attachment).isNull();
        assertThat(AriadneOtelBridge.currentSpan()).isNull();
    }

    @Test
    void unsampledFlagIsPreserved() {
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 0));
        assertThat(((TraceContext) AriadneContext.spawn(1).attachment).isSampled()).isFalse();
    }

    @Test
    void brokenOtelNeverBreaksTheApplication() {
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.brokenSpan());
        assertThat(AriadneContext.spawn(1).attachment).isNull();
    }

    @Test
    void conversionIsMemoisedPerSpanContext() {
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 1));
        TraceContext first = TraceContexts.current();
        TraceContext second = TraceContexts.current();
        assertThat(second).isSameAs(first);
    }

    @Test
    void spanIdentityFollowsTheSubmittingThreadAcrossTheHop() throws Exception {
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 1));
        Link link = AriadneContext.spawn(2);

        // Worker thread has NO otel span of its own (e.g. a plain pool): Ariadne restores identity
        AtomicReference<TraceContext> onWorker = new AtomicReference<>();
        Thread worker = new Thread(new AriadneRunnable(
                () -> onWorker.set(TraceContexts.current()), link));
        worker.start();
        worker.join();

        assertThat(onWorker.get()).isNotNull();
        assertThat(onWorker.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(onWorker.get().spanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void failureMessageCarriesTraceForLogCorrelation() {
        AriadneConfig.setExceptionContextEnabled(true);
        AriadneOtelBridge.install();
        StubOtel.makeCurrent(StubOtel.span(TRACE_ID, SPAN_ID, 1));
        Link link = AriadneContext.spawn(SiteRegistry.getOrRegister("otel-bridge-test"));

        AsyncCausalityException ex = AriadneReconstructor.buildSyntheticException(link);

        assertThat(ex.getMessage()).contains("[Trace: 00-" + TRACE_ID + "-" + SPAN_ID + "-01]");
    }

    @Test
    void installIfEnabledIsOptIn() {
        System.clearProperty(AriadneOtelBridge.ENABLE_PROPERTY);
        assertThat(AriadneOtelBridge.installIfEnabled()).isFalse();
        assertThat(AriadneOtelBridge.isInstalled()).isFalse();

        System.setProperty(AriadneOtelBridge.ENABLE_PROPERTY, "true");
        try {
            assertThat(AriadneOtelBridge.installIfEnabled()).isTrue();
            assertThat(AriadneOtelBridge.isInstalled()).isTrue();
        } finally {
            System.clearProperty(AriadneOtelBridge.ENABLE_PROPERTY);
        }
    }
}

