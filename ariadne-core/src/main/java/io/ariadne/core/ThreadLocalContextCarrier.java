package io.ariadne.core;

/**
 * Standard production carrier using {@link ThreadLocal} with guaranteed cleanup.
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
        Link parent = current();
        return new Link(parent, siteId, Thread.currentThread().threadId());
    }

    @Override
    public Scope attach(Link link) {
        Link previous = current();
        set(link);
        return () -> set(previous);
    }
}
