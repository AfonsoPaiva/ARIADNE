package io.ariadne.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global dynamic configuration for Ariadne.
 * <p>
 * Configuration options can be set via:
 * <ul>
 *   <li>System properties (e.g. {@code -Dariadne.max.depth=48})</li>
 *   <li>Environment variables (e.g. {@code ARIADNE_MAX_DEPTH=48})</li>
 *   <li>Programmatic API at runtime: {@link #setMaxDepth(int)}, etc.</li>
 *   <li>JMX Management MBean: {@code io.ariadne:type=AriadneManager}</li>
 * </ul>
 */
public final class AriadneConfig {

    public static final int DEFAULT_MAX_DEPTH = 32;
    public static final int MIN_MAX_DEPTH = 1;
    public static final int MAX_MAX_DEPTH = 1024;

    public static final boolean DEFAULT_CANARY_PROBES_ENABLED = true;
    public static final boolean DEFAULT_FAIL_FAST = false;
    public static final boolean DEFAULT_JMX_ENABLED = true;
    public static final boolean DEFAULT_MDC_PROPAGATION_ENABLED = true;

    private static final AtomicInteger MAX_DEPTH = new AtomicInteger(
            getIntProperty("ariadne.max.depth", "ARIADNE_MAX_DEPTH", DEFAULT_MAX_DEPTH, MIN_MAX_DEPTH, MAX_MAX_DEPTH)
    );

    private static final AtomicBoolean CANARY_PROBES_ENABLED = new AtomicBoolean(
            getBooleanProperty("ariadne.canary.probes.enabled", "ARIADNE_CANARY_PROBES_ENABLED", DEFAULT_CANARY_PROBES_ENABLED)
    );

    private static final AtomicBoolean FAIL_FAST = new AtomicBoolean(
            getBooleanProperty("ariadne.fail.fast", "ARIADNE_FAIL_FAST", DEFAULT_FAIL_FAST)
    );

    private static final AtomicBoolean JMX_ENABLED = new AtomicBoolean(
            getBooleanProperty("ariadne.jmx.enabled", "ARIADNE_JMX_ENABLED", DEFAULT_JMX_ENABLED)
    );

    private static final AtomicBoolean MDC_PROPAGATION_ENABLED = new AtomicBoolean(
            getBooleanProperty("ariadne.mdc.enabled", "ARIADNE_MDC_ENABLED", DEFAULT_MDC_PROPAGATION_ENABLED)
    );

    private AriadneConfig() {}

    /**
     * Gets the maximum recursion depth for causality reconstruction.
     */
    public static int getMaxDepth() {
        return MAX_DEPTH.get();
    }

    /**
     * Dynamically sets the maximum recursion depth for causality reconstruction.
     *
     * @param depth Value between {@link #MIN_MAX_DEPTH} and {@link #MAX_MAX_DEPTH}
     */
    public static void setMaxDepth(int depth) {
        if (depth < MIN_MAX_DEPTH || depth > MAX_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "maxDepth must be between " + MIN_MAX_DEPTH + " and " + MAX_MAX_DEPTH + ", got " + depth
            );
        }
        MAX_DEPTH.set(depth);
    }

    /**
     * Checks if canary health probes are enabled on adapter installation.
     */
    public static boolean isCanaryProbesEnabled() {
        return CANARY_PROBES_ENABLED.get();
    }

    /**
     * Dynamically enables or disables canary health probes.
     */
    public static void setCanaryProbesEnabled(boolean enabled) {
        CANARY_PROBES_ENABLED.set(enabled);
    }

    /**
     * Checks if framework compatibility checks should fail-fast with an exception.
     */
    public static boolean isFailFast() {
        return FAIL_FAST.get();
    }

    /**
     * Dynamically configures fail-fast mode.
     */
    public static void setFailFast(boolean failFast) {
        FAIL_FAST.set(failFast);
    }

    /**
     * Checks if JMX MBean registration is enabled.
     */
    public static boolean isJmxEnabled() {
        return JMX_ENABLED.get();
    }

    /**
     * Enables or disables JMX MBean registration.
     */
    public static void setJmxEnabled(boolean jmxEnabled) {
        JMX_ENABLED.set(jmxEnabled);
    }
    /**
     * Checks if automatic MDC (Mapped Diagnostic Context) propagation is enabled.
     */
    public static boolean isMdcPropagationEnabled() {
        return MDC_PROPAGATION_ENABLED.get();
    }

    /**
     * Enables or disables automatic MDC propagation across async boundaries.
     */
    public static void setMdcPropagationEnabled(boolean enabled) {
        MDC_PROPAGATION_ENABLED.set(enabled);
    }

    /**
     * Resets all configurations to their default values.
     */
    public static void resetDefaults() {
        MAX_DEPTH.set(DEFAULT_MAX_DEPTH);
        CANARY_PROBES_ENABLED.set(DEFAULT_CANARY_PROBES_ENABLED);
        FAIL_FAST.set(DEFAULT_FAIL_FAST);
        JMX_ENABLED.set(DEFAULT_JMX_ENABLED);
        MDC_PROPAGATION_ENABLED.set(DEFAULT_MDC_PROPAGATION_ENABLED);
    }

    private static int getIntProperty(String sysProp, String envVar, int defaultValue, int min, int max) {
        String val = System.getProperty(sysProp);
        if (val == null || val.isBlank()) {
            val = System.getenv(envVar);
        }
        if (val != null && !val.isBlank()) {
            try {
                int parsed = Integer.parseInt(val.trim());
                return Math.max(min, Math.min(max, parsed));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static boolean getBooleanProperty(String sysProp, String envVar, boolean defaultValue) {
        String val = System.getProperty(sysProp);
        if (val == null || val.isBlank()) {
            val = System.getenv(envVar);
        }
        if (val != null && !val.isBlank()) {
            return Boolean.parseBoolean(val.trim());
        }
        return defaultValue;
    }
}
