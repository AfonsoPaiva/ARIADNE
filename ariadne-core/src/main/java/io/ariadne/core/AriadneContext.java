package io.ariadne.core;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Context facade managing the propagation of {@link Link} instances along execution boundaries.
 * Delegates to a pluggable {@link ContextCarrier} (defaulting to {@link ThreadLocalContextCarrier}).
 */
public final class AriadneContext {

    private static volatile ContextCarrier CARRIER = new ThreadLocalContextCarrier();

    private AriadneContext() {}

    /**
     * Gets the active {@link ContextCarrier} implementation.
     */
    public static ContextCarrier carrier() {
        return CARRIER;
    }

    /**
     * Configures a custom {@link ContextCarrier} strategy.
     */
    public static void setCarrier(ContextCarrier carrier) {
        CARRIER = Objects.requireNonNull(carrier, "Carrier must not be null");
    }

    /**
     * Retrieves the active {@link Link} on the current context, or {@code null} if none is active.
     */
    public static Link current() {
        return CARRIER.current();
    }

    /**
     * Sets the active {@link Link} on the current context.
     * If {@code null}, clears the context.
     */
    public static void set(Link link) {
        CARRIER.set(link);
    }

    /**
     * Clears the active {@link Link} from the current context.
     */
    public static void clear() {
        CARRIER.clear();
    }

    /**
     * Spawns a new child {@link Link} rooted at the current active link (or null if none).
     *
     * @param siteId Call site identifier
     * @return New immutable {@link Link}
     */
    public static Link spawn(int siteId) {
        return spawn(siteId, null);
    }

    /**
     * Spawns a new child {@link Link} rooted at the current active link with an optional attachment.
     *
     * @param siteId     Call site identifier
     * @param attachment Optional contextual payload (e.g. MDC snapshot, trace correlation)
     * @return New immutable {@link Link}
     */
    public static Link spawn(int siteId, Object attachment) {
        AriadneMetrics.recordHop();
        if (TraceContexts.isEnabled()) {
            attachment = TraceContexts.mergeInto(attachment);
        }
        return CARRIER.spawn(siteId, attachment);
    }

    /**
     * Attaches a {@link Link} to the current context for the duration of a scoped block,
     * restoring the previous link upon closure.
     * <p>
     * If W3C trace propagation is enabled ({@link TraceContexts#enable()}) and the link carries a
     * trace context, that trace also becomes the ambient trace of the current thread for the
     * duration of the scope.
     */
    public static Scope attach(Link link) {
        ContextCarrier.Scope scope = CARRIER.attach(link);
        Scope base;
        if (scope instanceof Scope s) {
            base = s;
        } else {
            base = scope::close;
        }
        if (link != null && TraceContexts.isEnabled()) {
            TraceContext trace = TraceContexts.fromAttachment(link.attachment);
            if (trace != null) {
                Scope traceScope = TraceContexts.attach(trace);
                return () -> {
                    try {
                        traceScope.close();
                    } finally {
                        base.close();
                    }
                };
            }
        }
        return base;
    }

    /**
     * Executes a runnable within the scope of the given {@link Link}, restoring previous state.
     */
    public static void runWith(Link link, Runnable action) {
        try (Scope ignored = attach(link)) {
            action.run();
        }
    }

    /**
     * Executes a callable within the scope of the given {@link Link}, restoring previous state.
     */
    public static <V> V callWith(Link link, Callable<V> action) throws Exception {
        try (Scope ignored = attach(link)) {
            return action.call();
        }
    }

    /**
     * Scoped handle for restoring previous context.
     */
    @FunctionalInterface
    public interface Scope extends ContextCarrier.Scope {
        @Override
        void close();
    }
}

