package io.ariadne.core;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * JMX management singleton and registration provider for Ariadne.
 */
public final class AriadneManagement implements AriadneMXBean {

    public static final String MBEAN_OBJECT_NAME = "io.ariadne:type=AriadneManager";
    public static final String VERSION = "0.1.0-alpha.2";

    private static final AriadneManagement INSTANCE = new AriadneManagement();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private AriadneManagement() {}

    public static AriadneManagement getInstance() {
        return INSTANCE;
    }

    /**
     * Registers the Ariadne JMX MBean on the platform {@link MBeanServer}.
     * Idempotent and thread-safe. Fails gracefully if JMX is disabled or security-restricted.
     *
     * @return true if registered successfully or already registered, false otherwise.
     */
    public static synchronized boolean registerMBean() {
        if (!AriadneConfig.isJmxEnabled()) {
            return false;
        }
        if (REGISTERED.get()) {
            return true;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(MBEAN_OBJECT_NAME);
            if (!server.isRegistered(name)) {
                server.registerMBean(INSTANCE, name);
            }
            REGISTERED.set(true);
            return true;
        } catch (Throwable ignored) {
            // Silently allow execution in sandboxes or environments without JMX privileges
            return false;
        }
    }

    /**
     * Unregisters the Ariadne JMX MBean from the platform {@link MBeanServer}.
     *
     * @return true if successfully unregistered.
     */
    public static synchronized boolean unregisterMBean() {
        if (!REGISTERED.get()) {
            return false;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(MBEAN_OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            REGISTERED.set(false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Checks if the MBean is currently registered on the platform MBeanServer.
     */
    public static boolean isRegistered() {
        return REGISTERED.get();
    }

    @Override
    public int getMaxDepth() {
        return AriadneConfig.getMaxDepth();
    }

    @Override
    public void setMaxDepth(int depth) {
        AriadneConfig.setMaxDepth(depth);
    }

    @Override
    public boolean isCanaryProbesEnabled() {
        return AriadneConfig.isCanaryProbesEnabled();
    }

    @Override
    public void setCanaryProbesEnabled(boolean enabled) {
        AriadneConfig.setCanaryProbesEnabled(enabled);
    }

    @Override
    public boolean isFailFast() {
        return AriadneConfig.isFailFast();
    }

    @Override
    public void setFailFast(boolean failFast) {
        AriadneConfig.setFailFast(failFast);
    }

    @Override
    public boolean isMdcPropagationEnabled() {
        return AriadneConfig.isMdcPropagationEnabled();
    }

    @Override
    public void setMdcPropagationEnabled(boolean enabled) {
        AriadneConfig.setMdcPropagationEnabled(enabled);
    }

    @Override
    public String getCallSiteMode() {
        AriadneConfig.CallSiteMode mode = AriadneConfig.getCallSiteMode();
        if (mode == AriadneConfig.CallSiteMode.SAMPLED) {
            return "SAMPLED:" + AriadneConfig.getCallSiteSampleRate();
        }
        return mode.name();
    }

    @Override
    public void setCallSiteMode(String mode) {
        AriadneConfig.setCallSiteMode(mode);
    }

    @Override
    public long getHopsSpawned() {
        return AriadneMetrics.getHopsSpawned();
    }

    @Override
    public long getHopsCapped() {
        return AriadneMetrics.getHopsCapped();
    }

    @Override
    public long getReconstructionsTotal() {
        return AriadneMetrics.getReconstructionsTotal();
    }

    @Override
    public long getReconstructionsCapped() {
        return AriadneMetrics.getReconstructionsCapped();
    }

    @Override
    public int getRegisteredCallSitesCount() {
        return SiteRegistry.size();
    }

    @Override
    public Map<String, String> getCanaryHealth() {
        Map<String, String> health = new LinkedHashMap<>();
        for (Map.Entry<String, CanaryProbeResult> entry : AriadneMetrics.getCanaryResults().entrySet()) {
            CanaryProbeResult res = entry.getValue();
            health.put(entry.getKey(), res.isHealthy() ? "HEALTHY" : "DEGRADED: " + res.message());
        }
        return health;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public void resetMetrics() {
        AriadneMetrics.reset();
    }

    @Override
    public void clearSiteRegistry() {
        SiteRegistry.clear();
    }

    @Override
    public String dumpHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Ariadne Health Report ===\n");
        sb.append("Version: ").append(VERSION).append("\n");
        sb.append("Max Depth: ").append(getMaxDepth()).append("\n");
        sb.append("Canary Probes Enabled: ").append(isCanaryProbesEnabled()).append("\n");
        sb.append("Fail Fast: ").append(isFailFast()).append("\n");
        sb.append("MDC Propagation Enabled: ").append(isMdcPropagationEnabled()).append("\n");
        sb.append("Hops Spawned: ").append(getHopsSpawned()).append("\n");
        sb.append("Hops Capped: ").append(getHopsCapped()).append("\n");
        sb.append("Reconstructions Total: ").append(getReconstructionsTotal()).append("\n");
        sb.append("Reconstructions Capped: ").append(getReconstructionsCapped()).append("\n");
        sb.append("Registered Call Sites: ").append(getRegisteredCallSitesCount()).append("\n");
        sb.append("Canary Health Status:\n");
        Map<String, String> health = getCanaryHealth();
        if (health.isEmpty()) {
            sb.append("  (No adapter canary probes registered yet)\n");
        } else {
            health.forEach((k, v) -> sb.append("  [").append(k).append("]: ").append(v).append("\n"));
        }
        return sb.toString();
    }
}
