package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceContextPropagationTest {

    private static final String HEADER = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @BeforeEach
    void setUp() {
        TraceContexts.enable();
        AriadneContext.clear();
        AriadneConfig.setExceptionContextEnabled(true);
    }

    @AfterEach
    void tearDown() {
        TraceContexts.disable();
        TraceContexts.setSource(null);
        AriadneContext.clear();
        AriadneConfig.resetDefaults();
    }

    @Test
    void spawnSnapshotsAmbientTraceIntoLink() {
        TraceContext tc = TraceContext.parse(HEADER);
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            Link link = AriadneContext.spawn(1);
            assertThat(link.attachment).isEqualTo(tc);
        }
        assertThat(TraceContexts.current()).isNull();
    }

    @Test
    void traceCrossesThreadBoundaryThroughRunnableWrapper() throws Exception {
        TraceContext tc = TraceContext.parse(HEADER);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<TraceContext> seen = new AtomicReference<>();
            AtomicReference<TraceContext> afterRun = new AtomicReference<>(tc);

            Runnable wrapped;
            try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
                Link link = AriadneContext.spawn(7);
                wrapped = new AriadneRunnable(() -> seen.set(TraceContexts.current()), link);
            }
            pool.submit(wrapped).get(5, TimeUnit.SECONDS);
            pool.submit(() -> afterRun.set(TraceContexts.current())).get(5, TimeUnit.SECONDS);

            assertThat(seen.get()).isEqualTo(tc);
            // restored (cleared) on the worker once the scope closed: no leakage to the next task
            assertThat(afterRun.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mapAttachmentIsCopiedNotMutated() {
        TraceContext tc = TraceContext.parse(HEADER);
        Map<String, String> mdc = Map.of("requestId", "r-1");
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            Link link = AriadneContext.spawn(2, mdc);
            assertThat(link.attachment).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> merged = (Map<String, String>) link.attachment;
            assertThat(merged)
                    .containsEntry("requestId", "r-1")
                    .containsEntry("traceparent", HEADER);
        }
        assertThat(mdc).containsOnlyKeys("requestId");
        assertThat(TraceContexts.fromAttachment(Map.of("traceparent", HEADER))).isEqualTo(tc);
    }

    @Test
    void explicitMapTraceparentIsNotOverwritten() {
        TraceContext ambient = TraceContext.parse(HEADER);
        Map<String, String> mine = Map.of("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        try (AriadneContext.Scope ignored = TraceContexts.attach(ambient)) {
            assertThat(AriadneContext.spawn(2, mine).attachment).isSameAs(mine);
        }
    }

    @Test
    void externalSourceIsUsedWhenNothingIsAttached() {
        TraceContext tc = TraceContext.parse(HEADER);
        TraceContexts.setSource(() -> tc);
        assertThat(TraceContexts.current()).isEqualTo(tc);
        assertThat(AriadneContext.spawn(3).attachment).isEqualTo(tc);

        TraceContext explicit = tc.withNewSpanId();
        try (AriadneContext.Scope ignored = TraceContexts.attach(explicit)) {
            assertThat(TraceContexts.current()).isEqualTo(explicit);
        }
    }

    @Test
    void failingExternalSourceNeverBreaksTheApplication() {
        TraceContexts.setSource(() -> {
            throw new IllegalStateException("boom");
        });
        assertThat(TraceContexts.current()).isNull();
        assertThat(AriadneContext.spawn(4).attachment).isNull();
    }

    @Test
    void disabledMeansNoCaptureAndNoRestore() {
        TraceContexts.disable();
        TraceContext tc = TraceContext.parse(HEADER);
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            assertThat(AriadneContext.spawn(5).attachment).isNull();
        }
        Link carrying = new Link(null, 1, 1L, tc);
        try (AriadneContext.Scope ignored = AriadneContext.attach(carrying)) {
            assertThat(TraceContexts.current()).isNull();
        }
    }

    @Test
    void failureOnWorkerCarriesTraceInSyntheticException() throws Exception {
        TraceContext tc = TraceContext.parse(HEADER);
        Link link;
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            link = AriadneContext.spawn(SiteRegistry.getOrRegister("trace-test"));
        }
        AsyncCausalityException ex = AriadneReconstructor.buildSyntheticException(link);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("[Trace: " + HEADER + "]");
    }

    @Test
    void mapAttachmentWithTraceIsRenderedViaAllowlist() {
        TraceContext tc = TraceContext.parse(HEADER);
        Link link;
        try (AriadneContext.Scope ignored = TraceContexts.attach(tc)) {
            link = AriadneContext.spawn(SiteRegistry.getOrRegister("trace-map-test"), Map.of("requestId", "r-9"));
        }
        AsyncCausalityException ex = AriadneReconstructor.buildSyntheticException(link);
        assertThat(ex.getMessage()).contains("requestId=r-9").contains("traceparent=" + HEADER);
    }
}
