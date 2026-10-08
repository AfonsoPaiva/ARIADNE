package io.ariadne.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ambient W3C {@link TraceContext} propagation alongside the causal {@link Link} chain.
 * <p>
 * When enabled, every {@link AriadneContext#spawn(int, Object)} snapshots the ambient trace
 * context into the new {@link Link}'s attachment, and every {@link AriadneContext#attach(Link)}
 * restores it on the executing thread. The trace identity therefore crosses thread boundaries
 * through exactly the same hooks that carry the causal chain, with no extra instrumentation.
 * <p>
 * The ambient context comes from, in order:
 * <ol>
 *   <li>an explicit {@link #attach(TraceContext)} (e.g. a servlet filter that parsed an
 *       incoming {@code traceparent} header), then</li>
 *   <li>the optional {@linkplain #setSource(Supplier) external source} (e.g. the OpenTelemetry
 *       bridge reading the active span).</li>
 * </ol>
 * Disabled by default; enable with {@link #enable()} or {@code -Dariadne.trace.context.enabled=true}.
 * While disabled the hot path costs a single volatile read.
 */
public final class TraceContexts {

    /** Map key under which the trace is stored when merged into a context-map attachment. */
    public static final String TRACEPARENT_KEY = "traceparent";

    private static final ThreadLocal<TraceContext> AMBIENT = new ThreadLocal<>();

    private static volatile boolean enabled = Boolean.getBoolean("ariadne.trace.context.enabled");
    private static volatile Supplier<TraceContext> source;

    private TraceContexts() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    /**
     * Registers an external provider of the current trace (for example the active OpenTelemetry
     * span). Consulted only when no explicit context is attached. Pass {@code null} to remove.
     */
    public static void setSource(Supplier<TraceContext> traceSource) {
        source = traceSource;
    }

    /**
     * Returns the ambient trace context for the current thread, or {@code null} if none.
     * Never throws; a failing external source is treated as "no trace".
     */
    public static TraceContext current() {
        TraceContext explicit = AMBIENT.get();
        if (explicit != null) {
            return explicit;
        }
        Supplier<TraceContext> s = source;
        if (s != null) {
            try {
                return s.get();
            } catch (Throwable ignored) {
                // Fail-safe: a broken trace source must never disrupt the application
            }
        }
        return null;
    }

    /**
     * Makes {@code context} the ambient trace context of the current thread until the returned
     * scope is closed, which restores the previous value.
     */
    public static AriadneContext.Scope attach(TraceContext context) {
        TraceContext previous = AMBIENT.get();
        if (context == null) {
            AMBIENT.remove();
        } else {
            AMBIENT.set(context);
        }
        return () -> {
            if (previous == null) {
                AMBIENT.remove();
            } else {
                AMBIENT.set(previous);
            }
        };
    }

    /**
     * Extracts the trace context stored in a {@link Link} attachment: either a
     * {@link TraceContext} directly, or a {@code traceparent} entry of a context map.
     *
     * @return the trace context, or {@code null}
     */
    public static TraceContext fromAttachment(Object attachment) {
        if (attachment instanceof TraceContext tc) {
            return tc;
        }
        if (attachment instanceof Map<?, ?> map) {
            Object value = map.get(TRACEPARENT_KEY);
            if (value instanceof String s) {
                return TraceContext.parse(s);
            }
        }
        return null;
    }

    /**
     * Merges the ambient trace into a spawn attachment. Called by {@link AriadneContext#spawn}.
     * <ul>
     *   <li>no attachment: the {@link TraceContext} itself becomes the attachment;</li>
     *   <li>map attachment: a copy with a {@code traceparent} entry is returned (never mutates
     *       the caller's map);</li>
     *   <li>anything else: returned unchanged.</li>
     * </ul>
     */
    static Object mergeInto(Object attachment) {
        TraceContext tc = current();
        if (tc == null) {
            return attachment;
        }
        if (attachment == null) {
            return tc;
        }
        if (attachment instanceof Map<?, ?> map && !map.containsKey(TRACEPARENT_KEY)) {
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            copy.put(TRACEPARENT_KEY, tc.toTraceparent());
            return copy;
        }
        return attachment;
    }
}

