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

    /**
     * Prunes intermediate hops when the causal chain exceeds {@link AriadneConfig#getMaxDepth()}.
     * <p>
     * <b>Preserving the Origin (Root Preservation Guarantee):</b><br>
     * Standard FIFO sliding windows drop the oldest elements first. In asynchronous web applications,
     * the oldest hop is the HTTP Controller / ingress request entrypoint — the most critical anchor
     * for understanding the originating user request.
     * <p>
     * Ariadne guarantees that the <b>root origin hop</b> (where {@code parent == null}) is permanently
     * retained at the base of the chain, while sliding the intermediate window to retain the most recent
     * {@code (retainCount - 1)} hops leading to {@code current}.
     * <p>
     * <b>Tradeoff:</b> Intermediate hops between the root origin and the sliding window are pruned,
     * freeing memory and bounding chain depth to {@code O(maxDepth)} to prevent memory retention leaks
     * in unbounded reactive streams or recursive tasks, while strictly preserving both root cause origin
     * and immediate failure context.
     *
     * @param current     The active tail link
     * @param retainCount Total number of links to retain (including the root)
     * @return A newly linked chain with root at the base and recent hops attached
     */
    static Link pruneOldestHops(Link current, int retainCount) {
        if (current == null || retainCount <= 0) {
            return null;
        }

        // 1. Locate the root origin hop
        Link root = current;
        while (root.parent != null) {
            root = root.parent;
        }

        // If only 1 hop to retain, return the root link
        if (retainCount == 1 || current == root) {
            return new Link(null, root.siteId, root.threadId, root.attachment);
        }

        // 2. Collect the (retainCount - 1) most recent hops leading up to current (excluding root)
        int recentCount = retainCount - 1;
        Link[] recent = new Link[recentCount];
        Link node = current;
        int collected = 0;
        for (int i = recentCount - 1; i >= 0 && node != null && node != root; i--) {
            recent[i] = node;
            collected++;
            node = node.parent;
        }

        // 3. Rebuild chain starting with the root origin hop
        Link rebuilt = new Link(null, root.siteId, root.threadId, root.attachment);
        int startIndex = recentCount - collected;
        for (int i = startIndex; i < recentCount; i++) {
            Link orig = recent[i];
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
