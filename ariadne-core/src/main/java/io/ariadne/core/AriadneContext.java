package io.ariadne.core;

import java.util.concurrent.Callable;

/**
 * Context carrier managing the propagation of {@link Link} instances along thread boundaries.
 * <p>
 * Ensures zero-leak thread local management with explicit restore and scoped cleanup semantics.
 */
public final class AriadneContext {

    private static final ThreadLocal<Link> CURRENT_LINK = new ThreadLocal<>();

    private AriadneContext() {}

    /**
     * Retrieves the active {@link Link} on the current thread, or {@code null} if none is active.
     */
    public static Link current() {
        return CURRENT_LINK.get();
    }

    /**
     * Sets the active {@link Link} on the current thread.
     * If {@code null}, removes the thread local entry to prevent memory leaks.
     */
    public static void set(Link link) {
        if (link == null) {
            CURRENT_LINK.remove();
        } else {
            CURRENT_LINK.set(link);
        }
    }

    /**
     * Clears the active {@link Link} from the current thread.
     */
    public static void clear() {
        CURRENT_LINK.remove();
    }

    /**
     * Spawns a new child {@link Link} rooted at the current active link (or null if none).
     *
     * @param siteId Call site identifier
     * @return New immutable {@link Link}
     */
    public static Link spawn(int siteId) {
        Link parent = current();
        return new Link(parent, siteId, Thread.currentThread().threadId());
    }

    /**
     * Attaches a {@link Link} to the current thread for the duration of a scoped block,
     * restoring the previous link upon closure.
     */
    public static Scope attach(Link link) {
        Link previous = current();
        set(link);
        return () -> set(previous);
    }

    /**
     * Executes a runnable within the scope of the given {@link Link}, restoring previous state.
     */
    public static void runWith(Link link, Runnable action) {
        Link previous = current();
        set(link);
        try {
            action.run();
        } finally {
            set(previous);
        }
    }

    /**
     * Executes a callable within the scope of the given {@link Link}, restoring previous state.
     */
    public static <V> V callWith(Link link, Callable<V> action) throws Exception {
        Link previous = current();
        set(link);
        try {
            return action.call();
        } finally {
            set(previous);
        }
    }

    /**
     * Scoped handle for restoring previous context.
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
