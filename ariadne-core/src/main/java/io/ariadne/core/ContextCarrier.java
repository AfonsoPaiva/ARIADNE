package io.ariadne.core;

/**
 * Pluggable contract for context propagation across asynchronous boundaries.
 * <p>
 * Both framework hooks (Reactor, RxJava) and bytecode transformers (Executor, CompletableFuture)
 * interact strictly through this interface, ensuring architectural stability across versions.
 */
public interface ContextCarrier {

    /**
     * Retrieves the active {@link Link} on the current execution context.
     */
    Link current();

    /**
     * Sets the active {@link Link} on the current execution context.
     * Passing {@code null} clears the context to prevent leaks.
     */
    void set(Link link);

    /**
     * Explicitly clears the active {@link Link}.
     */
    void clear();

    /**
     * Spawns a new child {@link Link} rooted at the current active link.
     *
     * @param siteId Call site identifier
     * @return New immutable child {@link Link}
     */
    Link spawn(int siteId);
 
    /**
     * Spawns a new child {@link Link} rooted at the current active link with an optional attachment.
     *
     * @param siteId     Call site identifier
     * @param attachment Optional contextual payload (e.g. MDC snapshot, trace correlation)
     * @return New immutable child {@link Link}
     */
    default Link spawn(int siteId, Object attachment) {
        return spawn(siteId);
    }

    /**
     * Attaches a {@link Link} to the current context for a scoped duration,
     * restoring the previous link upon closure.
     */
    Scope attach(Link link);

    /**
     * Scoped handle for restoring previous context in try-with-resources blocks.
     */
    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
