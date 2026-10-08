package io.ariadne.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.Callable;

/**
 * {@link ContextCarrier} that binds the active {@link Link} through a JDK
 * {@code java.lang.ScopedValue} (JEP 446 preview in JDK 21, JEP 487/506 in later releases),
 * with a thread-local overlay so that the imperative {@link #set(Link)} / {@link #attach(Link)}
 * calls used by the agent keep working.
 * <p>
 * <b>Why:</b> a scoped value binding is immutable, lexically scoped and, unlike a
 * {@link ThreadLocal}, is inherited automatically by threads forked from a
 * {@code StructuredTaskScope}. Binding the causal link with {@link #runWhere(Link, Runnable)} or
 * {@link #callWhere(Link, Callable)} therefore needs no manual capture/restore around structured
 * concurrency, and can never leak past the end of the scope.
 * <p>
 * <b>Resolution order for {@link #current()}:</b> the thread-local overlay (set by
 * {@code set}/{@code attach}) wins; otherwise the scoped binding; otherwise {@code null}.
 * {@code runWhere}/{@code callWhere} clear the overlay for the duration of the scope so that the
 * new binding is what the scope observes.
 * <p>
 * The JDK API is accessed reflectively through {@link MethodHandle}s, so this class compiles and
 * loads on every supported JDK and has zero build-time dependency on preview APIs. Check
 * {@link #isSupported()} before constructing it; on JDK 21 the running JVM may additionally
 * require {@code --enable-preview}.
 */
public final class ScopedValueContextCarrier implements ContextCarrier {

    private static final MethodHandle NEW_INSTANCE;
    private static final MethodHandle IS_BOUND;
    private static final MethodHandle GET;
    private static final MethodHandle WHERE;
    private static final MethodHandle CARRIER_RUN;
    private static final boolean SUPPORTED;

    static {
        MethodHandle newInstance = null;
        MethodHandle isBound = null;
        MethodHandle get = null;
        MethodHandle where = null;
        MethodHandle run = null;
        boolean ok = false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> scopedValue = Class.forName("java.lang.ScopedValue");
            Class<?> carrier = Class.forName("java.lang.ScopedValue$Carrier");
            newInstance = lookup.findStatic(scopedValue, "newInstance", MethodType.methodType(scopedValue))
                    .asType(MethodType.methodType(Object.class));
            isBound = lookup.findVirtual(scopedValue, "isBound", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            get = lookup.findVirtual(scopedValue, "get", MethodType.methodType(Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class));
            where = lookup.findStatic(scopedValue, "where",
                            MethodType.methodType(carrier, scopedValue, Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
            run = lookup.findVirtual(carrier, "run", MethodType.methodType(void.class, Runnable.class))
                    .asType(MethodType.methodType(void.class, Object.class, Runnable.class));
            // Probe: a preview-gated JDK throws here when --enable-preview is missing
            Object probe = (Object) newInstance.invoke();
            ok = probe != null;
        } catch (Throwable unsupported) {
            ok = false;
        }
        NEW_INSTANCE = newInstance;
        IS_BOUND = isBound;
        GET = get;
        WHERE = where;
        CARRIER_RUN = run;
        SUPPORTED = ok;
    }

    private final Object scopedKey;
    private final ThreadLocal<Link> overlay = new ThreadLocal<>();

    /**
     * @throws UnsupportedOperationException if {@code java.lang.ScopedValue} is not usable on this JVM
     */
    public ScopedValueContextCarrier() {
        if (!SUPPORTED) {
            throw new UnsupportedOperationException(
                    "java.lang.ScopedValue is not available on this JVM (JDK 21 requires --enable-preview)");
        }
        try {
            this.scopedKey = (Object) NEW_INSTANCE.invoke();
        } catch (Throwable t) {
            throw new UnsupportedOperationException("Unable to create ScopedValue", t);
        }
    }

    /** Whether {@code java.lang.ScopedValue} is usable on the running JVM. */
    public static boolean isSupported() {
        return SUPPORTED;
    }

    /**
     * Installs a new instance as the active carrier of {@link AriadneContext}.
     *
     * @return the installed carrier
     * @throws UnsupportedOperationException if scoped values are not available
     */
    public static ScopedValueContextCarrier install() {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        AriadneContext.setCarrier(carrier);
        return carrier;
    }

    @Override
    public Link current() {
        Link local = overlay.get();
        if (local != null) {
            return local;
        }
        try {
            if ((boolean) IS_BOUND.invoke(scopedKey)) {
                return (Link) (Object) GET.invoke(scopedKey);
            }
        } catch (Throwable ignored) {
            // Fail-safe: never disrupt the application
        }
        return null;
    }

    @Override
    public void set(Link link) {
        if (link == null) {
            overlay.remove();
        } else {
            overlay.set(link);
        }
    }

    /** Clears the thread-local overlay. An enclosing scoped binding is immutable and stays visible. */
    @Override
    public void clear() {
        overlay.remove();
    }

    @Override
    public Link spawn(int siteId) {
        return spawn(siteId, null);
    }

    @Override
    public Link spawn(int siteId, Object attachment) {
        Link parent = current();
        int maxDepth = AriadneConfig.getMaxDepth();
        if (parent != null && parent.depth >= maxDepth) {
            AriadneMetrics.recordHopCapped();
            parent = ThreadLocalContextCarrier.pruneOldestHops(parent, Math.max(1, maxDepth / 2));
        }
        return new Link(parent, siteId, Thread.currentThread().threadId(), attachment);
    }

    @Override
    public Scope attach(Link link) {
        Link previous = overlay.get();
        set(link);
        return () -> set(previous);
    }

    /**
     * Runs {@code action} with {@code link} bound as the scoped causal link. The binding is
     * inherited by threads forked from a {@code StructuredTaskScope} inside {@code action}.
     */
    public void runWhere(Link link, Runnable action) {
        if (link == null) {
            action.run();
            return;
        }
        Link savedOverlay = overlay.get();
        overlay.remove();
        try {
            Object carrier = (Object) WHERE.invoke(scopedKey, (Object) link);
            CARRIER_RUN.invoke(carrier, action);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("ScopedValue binding failed", t);
        } finally {
            set(savedOverlay);
        }
    }

    /** Callable variant of {@link #runWhere(Link, Runnable)}; checked exceptions propagate unchanged. */
    public <V> V callWhere(Link link, Callable<V> action) throws Exception {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        runWhere(link, () -> {
            try {
                result[0] = action.call();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Exception e) {
            throw e;
        }
        if (failure[0] instanceof Error err) {
            throw err;
        }
        @SuppressWarnings("unchecked")
        V value = (V) result[0];
        return value;
    }
}

