package io.ariadne.core;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance, lock-free observability metrics and canary health tracker for Ariadne.
 * <p>
 * Uses {@link LongAdder} internally to ensure minimal contention across high-throughput threads.
 */
public final class AriadneMetrics {

    private static final LongAdder HOPS_SPAWNED = new LongAdder();
    private static final LongAdder RECONSTRUCTIONS_TOTAL = new LongAdder();
    private static final LongAdder RECONSTRUCTIONS_CAPPED = new LongAdder();
    private static final Map<String, CanaryProbeResult> CANARY_RESULTS = new ConcurrentHashMap<>();

    private AriadneMetrics() {}

    /**
     * Records a newly spawned causal hop along an asynchronous execution boundary.
     */
    public static void recordHop() {
        HOPS_SPAWNED.increment();
    }

    /**
     * Records a synthetic causality exception enrichment upon an error.
     */
    public static void recordReconstruction() {
        RECONSTRUCTIONS_TOTAL.increment();
    }

    /**
     * Records an asynchronous trace reconstruction that hit and was truncated at maxDepth.
     */
    public static void recordReconstructionCapped() {
        RECONSTRUCTIONS_CAPPED.increment();
    }

    /**
     * Registers or updates the canary probe diagnostic result for a given reactive framework adapter.
     */
    public static void recordCanaryResult(String framework, CanaryProbeResult result) {
        if (framework != null && result != null) {
            CANARY_RESULTS.put(framework, result);
        }
    }

    /**
     * Returns the total count of asynchronous hops tracked since startup.
     */
    public static long getHopsSpawned() {
        return HOPS_SPAWNED.sum();
    }

    /**
     * Returns the total count of exceptions enriched with causal stack traces.
     */
    public static long getReconstructionsTotal() {
        return RECONSTRUCTIONS_TOTAL.sum();
    }

    /**
     * Returns the count of causal chain reconstructions that were capped at maxDepth.
     */
    public static long getReconstructionsCapped() {
        return RECONSTRUCTIONS_CAPPED.sum();
    }

    /**
     * Returns an unmodifiable snapshot of canary health results for all installed adapters.
     */
    public static Map<String, CanaryProbeResult> getCanaryResults() {
        return Collections.unmodifiableMap(CANARY_RESULTS);
    }

    /**
     * Resets all metric counters and probe results.
     */
    public static void reset() {
        HOPS_SPAWNED.reset();
        RECONSTRUCTIONS_TOTAL.reset();
        RECONSTRUCTIONS_CAPPED.reset();
        CANARY_RESULTS.clear();
    }
}
