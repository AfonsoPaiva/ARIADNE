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
        int maxDepth = AriadneConfig.getMaxDepth();
        if (parent != null && parent.depth >= maxDepth) {
            AriadneMetrics.recordHopCapped();
            // Sliding window: retain recent (maxDepth / 2) hops, prune oldest to allow GC
            parent = pruneOldestHops(parent, Math.max(1, maxDepth / 2));
        }
        return new Link(parent, siteId, Thread.currentThread().threadId(), attachment);
    }

    private static Link pruneOldestHops(Link current, int retainCount) {
        if (current == null || retainCount <= 0) {
            return null;
        }
        Link[] chain = new Link[retainCount];
        Link node = current;
        for (int i = retainCount - 1; i >= 0 && node != null; i--) {
            chain[i] = node;
            node = node.parent;
        }
        Link rebuilt = null;
        for (int i = 0; i < retainCount; i++) {
            Link orig = chain[i];
            if (orig != null) {
                rebuilt = new Link(rebuilt, orig.siteId, orig.threadId, orig.attachment);
            }
        }
        return rebuilt;
    }

    @Override
    public Scope attach(Link link) {
        Link previous = current();
        set(link);
        return () -> set(previous);
    }
}
