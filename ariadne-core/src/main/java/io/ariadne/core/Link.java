package io.ariadne.core;

/**
 * Immutable node in the asynchronous causal chain.
 * <p>
 * Memory Layout on 64-bit JVM with Compressed OOPs (-XX:+UseCompressedOops):
 * <ul>
 *   <li>Object header: 12 bytes (8-byte mark word + 4-byte compressed klass pointer)</li>
 *   <li>Reference {@code parent}: 4 bytes (compressed pointer)</li>
 *   <li>Primitive {@code siteId}: 4 bytes (int)</li>
 *   <li>Primitive {@code threadId}: 8 bytes (long)</li>
 *   <li>Reference {@code attachment}: 4 bytes (compressed pointer)</li>
 *   <li>Primitive {@code depth}: 4 bytes (int, for O(1) depth queries and leak prevention)</li>
 *   <li>Padding / alignment: 4 bytes</li>
 *   <li><b>Total instance footprint: 40 bytes</b> (allocated in thread-local TLAB; 48 bytes without compressed OOPs)</li>
 * </ul>
 */
public final class Link {

    public final Link parent;
    public final int siteId;
    public final long threadId;
    public final Object attachment;
    public final int depth;

    public Link(Link parent, int siteId, long threadId) {
        this(parent, siteId, threadId, null);
    }

    public Link(Link parent, int siteId, long threadId, Object attachment) {
        this.parent = parent;
        this.siteId = siteId;
        this.threadId = threadId;
        this.attachment = attachment;
        this.depth = (parent == null) ? 1 : (parent.depth + 1);
    }

    /**
     * Returns the depth of this link in the causal chain in O(1) time.
     */
    public int depth() {
        return depth;
    }

    @Override
    public String toString() {
        return "Link{" +
                "siteId=" + siteId +
                ", threadId=" + threadId +
                ", depth=" + depth +
                ", hasParent=" + (parent != null) +
                '}';
    }
}
