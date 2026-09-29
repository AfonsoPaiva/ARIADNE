package io.ariadne.core;

import java.util.Objects;

/**
 * Execution wrapper for {@link Runnable} that propagates the captured causal {@link Link}
 * and attaches synthetic stack traces if execution throws an unhandled {@link Throwable}.
 */
public final class AriadneRunnable implements Runnable {

    private final Runnable target;
    private final Link capturedLink;

    public AriadneRunnable(Runnable target) {
        this(target, AriadneContext.current());
    }

    public AriadneRunnable(Runnable target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "Target runnable must not be null");
        this.capturedLink = capturedLink;
    }

    /**
     * Factory method preventing redundant wrapping.
     */
    public static Runnable wrap(Runnable runnable) {
        if (runnable == null || runnable instanceof AriadneRunnable) {
            return runnable;
        }
        return new AriadneRunnable(runnable);
    }

    /**
     * Factory method wrapping with an explicit link.
     */
    public static Runnable wrap(Runnable runnable, Link link) {
        if (runnable == null) {
            return null;
        }
        if (runnable instanceof AriadneRunnable ar) {
            if (ar.capturedLink == link) {
                return runnable;
            }
            return new AriadneRunnable(ar.unwrap(), link);
        }
        return new AriadneRunnable(runnable, link);
    }

    @Override
    public void run() {
        AriadneContext.Scope scope = null;
        try {
            scope = AriadneContext.attach(capturedLink);
        } catch (Throwable ignored) {
            // Fail-safe: if attaching fails, continue running target
        }
        try {
            target.run();
        } catch (Throwable t) {
            try {
                AriadneReconstructor.enrich(t, capturedLink);
            } catch (Throwable ignored) {
                // Fail-safe: never hide or corrupt original exception
            }
            throw t;
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public Runnable unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
