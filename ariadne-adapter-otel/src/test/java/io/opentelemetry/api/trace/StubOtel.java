package io.opentelemetry.api.trace;

import java.util.concurrent.atomic.AtomicInteger;

/** Mutable fake OpenTelemetry state for tests. */
public final class StubOtel {

    private static final SpanContext INVALID_CONTEXT = new Ctx("00000000000000000000000000000000", "0000000000000000", (byte) 0, false);
    private static final Span INVALID_SPAN = () -> INVALID_CONTEXT;

    public static final ThreadLocal<Span> CURRENT = ThreadLocal.withInitial(() -> INVALID_SPAN);
    public static final AtomicInteger SPAN_CONTEXT_READS = new AtomicInteger();

    private StubOtel() {}

    public static Span span(String traceId, String spanId, int flags) {
        SpanContext ctx = new Ctx(traceId, spanId, (byte) flags, true);
        return () -> {
            SPAN_CONTEXT_READS.incrementAndGet();
            return ctx;
        };
    }

    public static Span brokenSpan() {
        return () -> {
            throw new IllegalStateException("otel exploded");
        };
    }

    public static void makeCurrent(Span span) {
        CURRENT.set(span);
    }

    public static void reset() {
        CURRENT.remove();
    }

    private record Ctx(String traceId, String spanId, byte flags, boolean valid) implements SpanContext {
        @Override
        public String getTraceId() {
            return traceId;
        }

        @Override
        public String getSpanId() {
            return spanId;
        }

        @Override
        public TraceFlags getTraceFlags() {
            return () -> flags;
        }

        @Override
        public boolean isValid() {
            return valid;
        }
    }
}
