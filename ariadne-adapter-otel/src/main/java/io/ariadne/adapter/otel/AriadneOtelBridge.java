package io.ariadne.adapter.otel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.ariadne.core.TraceContext;
import io.ariadne.core.TraceContexts;

/**
 * Reflection bridge that synchronises the <em>active OpenTelemetry span</em> with Ariadne's
 * in-process causal chain.
 * <p>
 * Once {@linkplain #install() installed}, every asynchronous hop Ariadne records
 * ({@code AriadneContext.spawn}) snapshots the identity of the span that is current on the
 * submitting thread ({@code Span.current().getSpanContext()}). If a task later fails, the
 * synthetic {@code AsyncCausalityException} carries
 * {@code [Trace: 00-<traceId>-<spanId>-<flags>]}, so a stack trace in a plain log file can be
 * joined to the distributed trace in the tracing backend, with no extra infrastructure.
 * <p>
 * <b>Relationship to OpenTelemetry:</b> this bridge is read-only. It never creates, modifies or
 * ends spans, and it does not replace OpenTelemetry's own {@code Context} propagation, which
 * remains responsible for the span hierarchy. It complements it by adding what a trace does not
 * show: the exact in-process call-site chain that led to the failure.
 * <p>
 * <b>Zero coupling:</b> OpenTelemetry is reached only through {@link MethodHandle}s resolved
 * at {@link #install()} time, so this module has no compile or runtime dependency on
 * OpenTelemetry and is a no-op when the API is absent.
 */
public final class AriadneOtelBridge {

    /** System property that makes the Java agent install this bridge automatically. */
    public static final String ENABLE_PROPERTY = "ariadne.otel.bridge.enabled";

    private static final String SPAN_CLASS = "io.opentelemetry.api.trace.Span";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AriadneOtelBridge() {}

    /**
     * Installs the bridge and enables W3C trace propagation. Idempotent and thread-safe.
     *
     * @return {@code true} if the OpenTelemetry API was found and the bridge is active;
     *         {@code false} (and nothing changes) if the API is not on the classpath
     */
    public static synchronized boolean install() {
        if (INSTALLED.get()) {
            return true;
        }
        Supplier<TraceContext> reader = createReader();
        if (reader == null) {
            return false;
        }
        TraceContexts.setSource(reader);
        TraceContexts.enable();
        INSTALLED.set(true);
        return true;
    }

    /**
     * Installs the bridge only if {@code -Dariadne.otel.bridge.enabled=true} is set.
     * Used by the Java agent so that the bridge stays strictly opt-in.
     */
    public static boolean installIfEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY) && install();
    }

    /** Removes the bridge and disables W3C trace propagation. */
    public static synchronized void uninstall() {
        if (INSTALLED.compareAndSet(true, false)) {
            TraceContexts.setSource(null);
            TraceContexts.disable();
        }
    }

    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    /**
     * Reads the OpenTelemetry span that is current on the calling thread.
     *
     * @return its identity, or {@code null} if the bridge is not installed, no valid span is
     *         active, or OpenTelemetry cannot be read
     */
    public static TraceContext currentSpan() {
        if (!INSTALLED.get()) {
            return null;
        }
        return TraceContexts.current();
    }

    /** Resolves the OpenTelemetry API reflectively; {@code null} if it is unavailable or incompatible. */
    static Supplier<TraceContext> createReader() {
        Class<?> span = loadSpanClass();
        if (span == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> spanContext = span.getMethod("getSpanContext").getReturnType();
            Class<?> traceFlags = spanContext.getMethod("getTraceFlags").getReturnType();

            MethodHandle current = lookup.findStatic(span, "current", MethodType.methodType(span))
                    .asType(MethodType.methodType(Object.class));
            MethodHandle getSpanContext = lookup
                    .findVirtual(span, "getSpanContext", MethodType.methodType(spanContext))
                    .asType(MethodType.methodType(Object.class, Object.class));
            MethodHandle isValid = lookup
                    .findVirtual(spanContext, "isValid", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            MethodHandle getTraceId = lookup
                    .findVirtual(spanContext, "getTraceId", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            MethodHandle getSpanId = lookup
                    .findVirtual(spanContext, "getSpanId", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            MethodHandle getTraceFlags = lookup
                    .findVirtual(spanContext, "getTraceFlags", MethodType.methodType(traceFlags))
                    .asType(MethodType.methodType(Object.class, Object.class));
            MethodHandle asByte = lookup
                    .findVirtual(traceFlags, "asByte", MethodType.methodType(byte.class))
                    .asType(MethodType.methodType(byte.class, Object.class));

            return new SpanReader(current, getSpanContext, isValid, getTraceId, getSpanId, getTraceFlags, asByte);
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            return null;
        }
    }

    private static Class<?> loadSpanClass() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader[] candidates = {context, AriadneOtelBridge.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(SPAN_CLASS, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // try next loader
            }
        }
        return null;
    }

    /**
     * Reads the current span through cached method handles. Memoises the last conversion by
     * {@code SpanContext} identity: OpenTelemetry returns the same immutable instance for the
     * lifetime of a span, so repeated hops within one span allocate nothing.
     */
    private static final class SpanReader implements Supplier<TraceContext> {

        private final MethodHandle current;
        private final MethodHandle getSpanContext;
        private final MethodHandle isValid;
        private final MethodHandle getTraceId;
        private final MethodHandle getSpanId;
        private final MethodHandle getTraceFlags;
        private final MethodHandle asByte;

        private volatile Cached last;

        SpanReader(MethodHandle current, MethodHandle getSpanContext, MethodHandle isValid,
                   MethodHandle getTraceId, MethodHandle getSpanId, MethodHandle getTraceFlags,
                   MethodHandle asByte) {
            this.current = current;
            this.getSpanContext = getSpanContext;
            this.isValid = isValid;
            this.getTraceId = getTraceId;
            this.getSpanId = getSpanId;
            this.getTraceFlags = getTraceFlags;
            this.asByte = asByte;
        }

        @Override
        public TraceContext get() {
            try {
                Object span = (Object) current.invokeExact();
                if (span == null) {
                    return null;
                }
                Object spanContext = (Object) getSpanContext.invokeExact(span);
                if (spanContext == null || !(boolean) isValid.invokeExact(spanContext)) {
                    return null;
                }
                Cached cached = last;
                if (cached != null && cached.spanContext == spanContext) {
                    return cached.trace;
                }
                String traceId = (String) getTraceId.invokeExact(spanContext);
                String spanId = (String) getSpanId.invokeExact(spanContext);
                Object flags = (Object) getTraceFlags.invokeExact(spanContext);
                byte flagByte = (byte) asByte.invokeExact(flags);
                TraceContext trace = TraceContext.of(traceId, spanId, flagByte);
                last = new Cached(spanContext, trace);
                return trace;
            } catch (Throwable unreadable) {
                // Fail-safe: telemetry correlation must never disrupt the application
                return null;
            }
        }
    }

    private record Cached(Object spanContext, TraceContext trace) {}
}

