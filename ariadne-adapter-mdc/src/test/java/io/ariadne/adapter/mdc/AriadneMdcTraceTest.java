package io.ariadne.adapter.mdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.Link;
import io.ariadne.core.TraceContext;
import io.ariadne.core.TraceContexts;

/** W3C trace propagation and SLF4J MDC propagation must compose, not shadow each other. */
class AriadneMdcTraceTest {

    private static final String HEADER = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @BeforeEach
    void setUp() {
        MDC.clear();
        AriadneMdcAdapter.install();
        TraceContexts.enable();
    }

    @AfterEach
    void tearDown() {
        TraceContexts.disable();
        AriadneMdcAdapter.uninstall();
        AriadneContext.clear();
        MDC.clear();
        AriadneConfig.resetDefaults();
    }

    @Test
    void spawnKeepsBothMdcAndTrace() {
        MDC.put("requestId", "req-42");
        TraceContext tc = TraceContext.parse(HEADER);
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            Link link = AriadneContext.spawn(1);
            assertThat(link.attachment).isInstanceOf(Map.class);
            Map<?, ?> map = (Map<?, ?>) link.attachment;
            assertThat(map.get("requestId")).isEqualTo("req-42");
            assertThat(map.get("traceparent")).isEqualTo(HEADER);
        }
    }

    @Test
    void workerThreadSeesRestoredMdcAndAmbientTrace() throws Exception {
        MDC.put("requestId", "req-7");
        TraceContext tc = TraceContext.parse(HEADER);
        Link link;
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            link = AriadneContext.spawn(2);
        }
        MDC.clear();

        AtomicReference<String> mdcSeen = new AtomicReference<>();
        AtomicReference<TraceContext> traceSeen = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (AriadneContext.Scope ignored = AriadneContext.attach(link)) {
                mdcSeen.set(MDC.get("requestId"));
                traceSeen.set(TraceContexts.current());
            }
        });
        worker.start();
        worker.join();

        assertThat(mdcSeen.get()).isEqualTo("req-7");
        assertThat(traceSeen.get()).isEqualTo(tc);
    }

    @Test
    void traceWithoutMdcIsStillCarriedAsBareContext() {
        TraceContext tc = TraceContext.parse(HEADER);
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            assertThat(AriadneContext.spawn(3).attachment).isEqualTo(tc);
        }
    }

    @Test
    void userSuppliedMdcTraceparentWins() {
        MDC.put("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        TraceContext tc = TraceContext.parse(HEADER);
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            Map<?, ?> map = (Map<?, ?>) AriadneContext.spawn(4).attachment;
            assertThat(map.get("traceparent")).isEqualTo("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        }
    }
}

