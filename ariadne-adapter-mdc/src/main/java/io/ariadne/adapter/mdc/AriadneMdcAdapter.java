package io.ariadne.adapter.mdc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneManagement;
import io.ariadne.core.AriadneMetrics;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.ContextCarrier;

/**
 * Adapter integrating Ariadne with SLF4J {@link MDC} (Mapped Diagnostic Context).
 * <p>
 * When installed, any asynchronous hop created via {@link AriadneContext#spawn(int)}
 * (including those intercepted by the Java agent or reactive adapters) captures the active
 * MDC map and restores it upon task execution in the destination worker thread.
 */
public final class AriadneMdcAdapter {

    private static final Logger log = LoggerFactory.getLogger(AriadneMdcAdapter.class);
    private static final String FRAMEWORK_NAME = "SLF4J MDC";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile ContextCarrier ORIGINAL_CARRIER = null;
    private static volatile Object ORIGINAL_BOOTSTRAP_CARRIER = null;

    private AriadneMdcAdapter() {}

    /**
     * Installs the MDC adapter, wrapping the current {@link ContextCarrier} with {@link AriadneMdcCarrier}.
     * Idempotent and thread-safe.
     */
    public static synchronized void install() {
        if (INSTALLED.get()) {
            return;
        }

        ORIGINAL_CARRIER = AriadneContext.carrier();
        AriadneContext.setCarrier(new AriadneMdcCarrier(ORIGINAL_CARRIER));

        // If AriadneContext was also loaded by Bootstrap ClassLoader, synchronize its carrier too
        try {
            Class<?> bootContext = Class.forName("io.ariadne.core.AriadneContext", false, null);
            if (bootContext != AriadneContext.class) {
                synchronizeBootstrapCarrier(bootContext);
            }
        } catch (ReflectiveOperationException ignored) {
            // Not on bootstrap or reflection restricted
        }

        INSTALLED.set(true);

        if (AriadneConfig.isCanaryProbesEnabled()) {
            CanaryProbeResult result = runCanaryProbe();
            AriadneMetrics.recordCanaryResult(FRAMEWORK_NAME, result);
            if (!result.isHealthy() && AriadneConfig.isFailFast()) {
                uninstall();
                throw new IllegalStateException("Ariadne MDC canary self-test failed: " + result.message(), result.error());
            }
        }

        AriadneManagement.registerMBean();
    }

    static void synchronizeBootstrapCarrier(Class<?> bootContext) throws ReflectiveOperationException {
        Object bootCarrier = bootContext.getMethod("carrier").invoke(null);
        ORIGINAL_BOOTSTRAP_CARRIER = bootCarrier;
        ClassLoader cl = bootContext.getClassLoader();
        Class<?> bootCarrierType = Class.forName("io.ariadne.core.ContextCarrier", false, cl);
        Class<?> bootScopeType = Class.forName("io.ariadne.core.ContextCarrier$Scope", false, cl);
        Class<?> bootLinkType = Class.forName("io.ariadne.core.Link", false, cl);

        Object proxy = createBootstrapCarrierProxy(bootCarrierType, bootScopeType, bootLinkType, bootCarrier);
        bootContext.getMethod("setCarrier", bootCarrierType).invoke(null, proxy);
    }

    static Object createBootstrapCarrierProxy(
            Class<?> bootCarrierType,
            Class<?> bootScopeType,
            Class<?> bootLinkType,
            Object bootCarrier) {
        return Proxy.newProxyInstance(
                bootCarrierType.getClassLoader(),
                new Class<?>[]{ bootCarrierType },
                (proxy1, method, args) -> {
                    String name = method.getName();
                    if ("spawn".equals(name)) {
                        if (args != null && args.length >= 1) {
                            int siteId = (int) args[0];
                            Object payload = AriadneMdcCarrier.resolvePayload((args.length > 1) ? args[1] : null);
                            Method spawnWithAttach = bootCarrier.getClass().getMethod("spawn", int.class, Object.class);
                            return spawnWithAttach.invoke(bootCarrier, siteId, payload);
                        } else {
                            return method.invoke(bootCarrier, args);
                        }
                    } else if ("attach".equals(name)) {
                        Object linkObj = (args != null && args.length > 0) ? args[0] : null;
                        Object delegateScope = method.invoke(bootCarrier, args);
                        if (!AriadneConfig.isMdcPropagationEnabled() || linkObj == null) {
                            return delegateScope;
                        }
                        Field attachField = bootLinkType.getField("attachment");
                        Object attachment = attachField.get(linkObj);
                        if (!(attachment instanceof Map<?, ?> targetMdc)) {
                            return delegateScope;
                        }
                        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                        @SuppressWarnings("unchecked")
                        Map<String, String> typedTargetMdc = (Map<String, String>) targetMdc;
                        MDC.setContextMap(typedTargetMdc);

                        return Proxy.newProxyInstance(
                                bootScopeType.getClassLoader(),
                                new Class<?>[]{ bootScopeType },
                                (scopeProxy, scopeMethod, scopeArgs) -> {
                                    if ("close".equals(scopeMethod.getName())) {
                                        try {
                                            if (previousMdc == null || previousMdc.isEmpty()) {
                                                MDC.clear();
                                            } else {
                                                MDC.setContextMap(previousMdc);
                                            }
                                        } finally {
                                            bootScopeType.getMethod("close").invoke(delegateScope);
                                        }
                                        return null;
                                    }
                                    return scopeMethod.invoke(delegateScope, scopeArgs);
                                }
                        );
                    }
                    return method.invoke(bootCarrier, args);
                }
        );
    }

    static void restoreBootstrapCarrier(Class<?> bootContext) {
        if (ORIGINAL_BOOTSTRAP_CARRIER != null && bootContext != null) {
            try {
                Class<?> bootCarrierType = Class.forName("io.ariadne.core.ContextCarrier", false, bootContext.getClassLoader());
                bootContext.getMethod("setCarrier", bootCarrierType).invoke(null, ORIGINAL_BOOTSTRAP_CARRIER);
            } catch (ReflectiveOperationException e) {
                log.debug("Ariadne MDC: could not restore bootstrap carrier via reflection (agent not attached?)", e);
            }
            ORIGINAL_BOOTSTRAP_CARRIER = null;
        }
    }

    /**
     * Uninstalls the MDC adapter, restoring the original {@link ContextCarrier}.
     */
    public static synchronized void uninstall() {
        if (!INSTALLED.get()) {
            return;
        }

        if (ORIGINAL_CARRIER != null) {
            AriadneContext.setCarrier(ORIGINAL_CARRIER);
            ORIGINAL_CARRIER = null;
        }

        if (ORIGINAL_BOOTSTRAP_CARRIER != null) {
            try {
                Class<?> bootContext = Class.forName("io.ariadne.core.AriadneContext", false, null);
                restoreBootstrapCarrier(bootContext);
            } catch (ReflectiveOperationException e) {
                log.debug("Ariadne MDC: bootstrap AriadneContext not found via null classloader (agent not attached?)", e);
            }
            ORIGINAL_BOOTSTRAP_CARRIER = null;
        }

        INSTALLED.set(false);
    }

    /**
     * Checks if the MDC adapter is currently installed.
     */
    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    /**
     * Runs a self-diagnostic canary probe verifying that MDC context propagates correctly
     * across an asynchronous boundary without leaking state.
     */
    public static CanaryProbeResult runCanaryProbe() {
        String testKey = "ariadne.canary.mdc";
        String testVal = "probe-" + System.nanoTime();
        Map<String, String> previousContext = MDC.getCopyOfContextMap();

        try {
            MDC.put(testKey, testVal);

            // Verify MDC preservation inside AriadneContext scope
            var link = AriadneContext.spawn(999999);
            MDC.remove(testKey);
            String capturedInScope;
            try (AriadneContext.Scope scope = AriadneContext.attach(link)) {
                assert scope != null;
                capturedInScope = MDC.get(testKey);
            }

            if (!testVal.equals(capturedInScope)) {
                return CanaryProbeResult.failure(FRAMEWORK_NAME, "MDC value was not preserved across AriadneContext scope");
            }

            return CanaryProbeResult.success(FRAMEWORK_NAME, "MDC propagation verified successfully");
        } catch (Exception t) {
            return CanaryProbeResult.failure(FRAMEWORK_NAME, "Canary probe threw unexpected exception: " + t.getMessage(), t);
        } finally {
            if (previousContext == null || previousContext.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousContext);
            }
        }
    }

    /**
     * Wraps a {@link Runnable} to capture current MDC and restore it during execution.
     */
    public static Runnable wrap(Runnable runnable) {
        Objects.requireNonNull(runnable, "Runnable must not be null");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (previous != null && !previous.isEmpty()) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wraps a {@link Callable} to capture current MDC and restore it during execution.
     */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        Objects.requireNonNull(callable, "Callable must not be null");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                return callable.call();
            } finally {
                if (previous != null && !previous.isEmpty()) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
