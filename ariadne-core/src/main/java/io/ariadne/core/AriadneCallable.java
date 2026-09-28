package io.ariadne.core;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Execution wrapper for {@link Callable} that propagates the captured causal {@link Link}
 * and attaches synthetic stack traces if execution throws an unhandled {@link Throwable}.
 */
public final class AriadneCallable<V> implements Callable<V> {

    private final Callable<V> target;
    private final Link capturedLink;

    public AriadneCallable(Callable<V> target) {
        this(target, AriadneContext.current());
    }

    public AriadneCallable(Callable<V> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "Target callable must not be null");
        this.capturedLink = capturedLink;
    }

    /**
     * Factory method preventing redundant wrapping.
     */
    public static <T> Callable<T> wrap(Callable<T> callable) {
        if (callable == null || callable instanceof AriadneCallable) {
            return callable;
        }
        return new AriadneCallable<>(callable);
    }

    /**
     * Factory method wrapping with an explicit link.
     */
    public static <T> Callable<T> wrap(Callable<T> callable, Link link) {
        if (callable == null) {
            return null;
        }
        if (callable instanceof AriadneCallable ac && ac.capturedLink == link) {
            return callable;
        }
        return new AriadneCallable<>(callable, link);
    }

    @Override
    public V call() throws Exception {
        Link previous = AriadneContext.current();
        AriadneContext.set(capturedLink);
        try {
            return target.call();
        } catch (Exception | Error t) {
            AriadneReconstructor.enrich(t, capturedLink);
            throw t;
        } finally {
            AriadneContext.set(previous);
        }
    }

    public Callable<V> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
