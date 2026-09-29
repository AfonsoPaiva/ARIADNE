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
        try {
            if (!AriadneConfig.isEnabled()) {
                return;
            }
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
        } catch (Throwable ignored) {
            // Fail-safe: agent must NEVER disrupt application execution
        }
    }

    /**
     * Reconstructs an {@link AsyncCausalityException} by traversing the causal link chain.
     */
    public static AsyncCausalityException buildSyntheticException(Link rootLink) {
        try {
            if (rootLink == null || !AriadneConfig.isEnabled()) {
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

            StringBuilder msg = new StringBuilder("Asynchronous execution path (")
                    .append(hops).append(" hop").append(hops > 1 ? "s" : "").append(")");

            if (AriadneConfig.isExceptionContextEnabled()) {
                Object contextAttachment = null;
                Link scan = rootLink;
                while (scan != null) {
                    if (scan.attachment != null) {
                        contextAttachment = scan.attachment;
                        break;
                    }
                    scan = scan.parent;
                }
                if (contextAttachment instanceof java.util.Map<?, ?> map) {
                    String safeContext = formatSafeContextMap(map);
                    if (safeContext != null && !safeContext.isEmpty()) {
                        msg.append(" [Context: ").append(safeContext).append("]");
                    }
                } else if (contextAttachment != null) {
                    try {
                        String str = String.valueOf(contextAttachment);
                        if (str.length() > AriadneConfig.DEFAULT_MDC_MAX_VALUE_LENGTH) {
                            str = str.substring(0, AriadneConfig.DEFAULT_MDC_MAX_VALUE_LENGTH) + "...";
                        }
                        msg.append(" [Context: ").append(str).append("]");
                    } catch (Throwable ignored) {}
                }
            }

            AsyncCausalityException exception = new AsyncCausalityException(msg.toString());
            exception.setStackTrace(elements.toArray(new StackTraceElement[0]));
            return exception;
        } catch (Throwable ignored) {
            // Fail-safe: never throw from reconstruction
            return null;
        }
    }

    private static String formatSafeContextMap(java.util.Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        java.util.Map<String, String> filtered = new java.util.TreeMap<>();
        int count = 0;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= AriadneConfig.DEFAULT_MDC_MAX_ENTRIES) {
                break;
            }
            try {
                String key = String.valueOf(entry.getKey());
                if (AriadneConfig.isMdcKeyAllowed(key)) {
                    String value = String.valueOf(entry.getValue());
                    if (value.length() > AriadneConfig.DEFAULT_MDC_MAX_VALUE_LENGTH) {
                        value = value.substring(0, AriadneConfig.DEFAULT_MDC_MAX_VALUE_LENGTH) + "...[truncated]";
                    }
                    filtered.put(key, value);
                    count++;
                }
            } catch (Throwable ignored) {}
        }
        return filtered.isEmpty() ? null : filtered.toString();
    }
}
