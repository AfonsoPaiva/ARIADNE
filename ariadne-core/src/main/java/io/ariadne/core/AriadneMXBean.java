package io.ariadne.core;

import java.util.Map;

/**
 * Standard JMX MBean interface for monitoring and dynamically configuring Ariadne in production.
 */
public interface AriadneMXBean {

    // Configuration Attributes (Readable & Writable)

    /**
     * Gets the current maximum depth of causality reconstruction.
     */
    int getMaxDepth();

    /**
     * Dynamically sets the maximum depth of causality reconstruction.
     */
    void setMaxDepth(int depth);

    /**
     * Returns true if canary self-test probes are enabled.
     */
    boolean isCanaryProbesEnabled();

    /**
     * Dynamically enables or disables canary self-test probes.
     */
    void setCanaryProbesEnabled(boolean enabled);

    /**
     * Returns true if framework compatibility check will fail-fast with an exception.
     */
    boolean isFailFast();

    /**
     * Dynamically configures fail-fast mode.
     */
    void setFailFast(boolean failFast);

    /**
     * Returns true if automatic MDC (Mapped Diagnostic Context) propagation is enabled.
     */
    boolean isMdcPropagationEnabled();

    /**
     * Dynamically enables or disables automatic MDC propagation across async boundaries.
     */
    void setMdcPropagationEnabled(boolean enabled);

    /**
     * Gets the current call site tracking mode (e.g. CLASS, SAMPLED:100, FULL).
     */
    String getCallSiteMode();

    /**
     * Dynamically sets the call site tracking mode (e.g. "class", "sampled:50", "full").
     */
    void setCallSiteMode(String mode);

    // Observability & Metrics Attributes (Read-only)

    /**
     * Returns the total number of asynchronous hops spawned across the JVM.
     */
    long getHopsSpawned();

    /**
     * Returns the number of causal hops where parent retention was capped to prevent memory leaks.
     */
    long getHopsCapped();

    /**
     * Returns the total number of exceptions enriched with causal stack traces.
     */
    long getReconstructionsTotal();

    /**
     * Returns the number of causal traces that exceeded maxDepth and were capped.
     */
    long getReconstructionsCapped();

    /**
     * Returns the count of distinct call sites cached in the SiteRegistry.
     */
    int getRegisteredCallSitesCount();

    /**
     * Returns a human-readable health map for all installed adapters.
     */
    Map<String, String> getCanaryHealth();

    /**
     * Returns the current version of Ariadne.
     */
    String getVersion();

    // Management Operations

    /**
     * Resets all metric counters to zero.
     */
    void resetMetrics();

    /**
     * Clears cached call sites from the global SiteRegistry.
     */
    void clearSiteRegistry();

    /**
     * Dumps a complete textual diagnostic and health report.
     */
    String dumpHealthReport();
}
