package io.ariadne.core;

import java.util.Objects;

/**
 * Diagnostic result of a framework canary probe self-test.
 * Used to detect silent hook bypasses or framework regression issues.
 */
public record CanaryProbeResult(boolean isHealthy, String framework, String message, Throwable error) {

    public CanaryProbeResult {
        Objects.requireNonNull(framework, "framework must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static CanaryProbeResult success(String framework) {
        return new CanaryProbeResult(true, framework, framework + " canary probe successfully verified causality propagation.", null);
    }

    public static CanaryProbeResult success(String framework, String message) {
        return new CanaryProbeResult(true, framework, message, null);
    }

    public static CanaryProbeResult failure(String framework, String reason) {
        return new CanaryProbeResult(false, framework, framework + " canary probe failed: " + reason, null);
    }

    public static CanaryProbeResult failure(String framework, String reason, Throwable error) {
        return new CanaryProbeResult(false, framework, framework + " canary probe failed: " + reason, error);
    }
}
