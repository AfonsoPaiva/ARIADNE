package io.ariadne.core;

/**
 * Standard production carrier using {@link ThreadLocal} with guaranteed cleanup.
 * <p>
 * Enforces causal depth capping at {@link AriadneConfig#getMaxDepth()} during {@link #spawn(int, Object)}
 * to prevent memory retention leaks in long-lived, repeating, or recursive async tasks.
 */
public final class ThreadLocalContextCarrier implements ContextCarrier {

    private final ThreadLocal<Link> currentLink = new ThreadLocal<>();

    @Override
    public Link current() {
        return currentLink.get();
    }

    @Override
    public void set(Link link) {
        if (link == null) {
            currentLink.remove();
        } else {
            currentLink.set(link);
        }
    }

    @Override
    public void clear() {
        currentLink.remove();
    }

    @Override
    public Link spawn(int siteId) {
        return spawn(siteId, null);
    }

    @Override
    public Link spawn(int siteId, Object attachment) {
        Link parent = current();
        if (parent != null && parent.depth >= AriadneConfig.getMaxDepth()) {
            AriadneMetrics.recordHopCapped();
            parent = null;
        }
        return new Link(parent, siteId, Thread.currentThread().threadId(), attachment);
    }

    @Override
    public Scope attach(Link link) {
        Link previous = current();
        set(link);
        return () -> set(previous);
    }
}
