package io.opentelemetry.api.trace;

/** Test stub mirroring the subset of the OpenTelemetry API read by the bridge. */
public interface SpanContext {

    String getTraceId();

    String getSpanId();

    TraceFlags getTraceFlags();

    boolean isValid();
}
