package io.ariadne.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazy reconstructor that materializes a synthetic asynchronous stack trace
 * from a {@link Link} chain only when an exception occurs.
 */
public final class AriadneReconstructor {

    static {
        // Attempt lazy JMX MBean registration upon first loading reconstructor
        AriadneManagement.registerMBean();
    }

    private AriadneReconstructor() {}

    /**
     * Enriches the provided {@link Throwable} with an asynchronous synthetic stack trace
     * attached as a suppressed exception.
     *
     * @param target The caught exception to enrich
     * @param link   The causal link at the point of failure
     */
    public static void enrich(Throwable target, Link link) {
        if (target == null || link == null) {
            return;
        }

        // Avoid duplicate enrichment across multiple async catch boundaries
        for (Throwable suppressed : target.getSuppressed()) {
            if (suppressed instanceof AsyncCausalityException) {
                return;
            }
        }

        AsyncCausalityException synthetic = buildSyntheticException(link);
        if (synthetic != null) {
            AriadneMetrics.recordReconstruction();
            target.addSuppressed(synthetic);
        }
    }

    /**
     * Reconstructs an {@link AsyncCausalityException} by traversing the causal link chain.
     */
    public static AsyncCausalityException buildSyntheticException(Link rootLink) {
        if (rootLink == null) {
            return null;
        }

        int maxDepth = AriadneConfig.getMaxDepth();
        List<StackTraceElement> elements = new ArrayList<>();
        Link current = rootLink;
        int hops = 0;

        while (current != null && hops < maxDepth) {
            CallSiteMetadata meta = SiteRegistry.get(current.siteId);
            StackTraceElement element;
            if (meta != null) {
                element = new StackTraceElement(
                        meta.className(),
                        meta.methodName(),
                        meta.fileName(),
                        meta.lineNumber()
                );
            } else {
                element = new StackTraceElement(
                        "io.ariadne.AsyncBoundary",
                        "continuation_site_" + current.siteId,
                        "thread_" + current.threadId,
                        -1
                );
            }
            elements.add(element);
            hops++;
            current = current.parent;
        }

        if (current != null) {
            // There was more causality beyond maxDepth
            AriadneMetrics.recordReconstructionCapped();
        }

        if (elements.isEmpty()) {
            return null;
        }

        AsyncCausalityException exception = new AsyncCausalityException(
                "Asynchronous execution path (" + hops + " hop" + (hops > 1 ? "s" : "") + ")"
        );
        exception.setStackTrace(elements.toArray(new StackTraceElement[0]));
        return exception;
    }
}
