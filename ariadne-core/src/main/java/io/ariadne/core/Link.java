package io.ariadne.core;

/**
 * Immutable node in the asynchronous causal chain.
 * <p>
 * Designed to be as lightweight as possible: holds only a reference to the parent link,
 * a pre-resolved integer site identifier, and the thread identifier that spawned the hop.
 * It allocates only a small heap object (~24-32 bytes) with zero stack walk on creation.
 */
public final class Link {

    public final Link parent;
    public final int siteId;
    public final long threadId;

    public Link(Link parent, int siteId, long threadId) {
        this.parent = parent;
        this.siteId = siteId;
        this.threadId = threadId;
    }

    /**
     * Calculates the depth of this link in the causal chain.
     */
    public int depth() {
        int d = 1;
        Link current = parent;
        while (current != null) {
            d++;
            current = current.parent;
        }
        return d;
    }

    @Override
    public String toString() {
        return "Link{" +
                "siteId=" + siteId +
                ", threadId=" + threadId +
                ", hasParent=" + (parent != null) +
                '}';
    }
}
