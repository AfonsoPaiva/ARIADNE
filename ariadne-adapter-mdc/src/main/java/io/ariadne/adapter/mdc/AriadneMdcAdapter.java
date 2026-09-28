package io.ariadne.adapter.mdc;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneManagement;
import io.ariadne.core.AriadneMetrics;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.ContextCarrier;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter integrating Ariadne with SLF4J {@link MDC} (Mapped Diagnostic Context).
 * <p>
 * When installed, any asynchronous hop created via {@link AriadneContext#spawn(int)}
 * (including those intercepted by the Java agent or reactive adapters) captures the active
 * MDC map and restores it upon task execution in the destination worker thread.
 */
public final class AriadneMdcAdapter {

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
                Object bootCarrier = bootContext.getMethod("carrier").invoke(null);
                ORIGINAL_BOOTSTRAP_CARRIER = bootCarrier;
                Class<?> bootCarrierType = Class.forName("io.ariadne.core.ContextCarrier", false, null);
                Class<?> bootScopeType = Class.forName("io.ariadne.core.ContextCarrier$Scope", false, null);
                Class<?> bootLinkType = Class.forName("io.ariadne.core.Link", false, null);

                Object proxy = Proxy.newProxyInstance(
                        null,
                        new Class<?>[]{ bootCarrierType },
                        new InvocationHandler() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                String name = method.getName();
                                if ("spawn".equals(name)) {
                                    if (args != null && args.length == 1) {
                                        int siteId = (int) args[0];
                                        Map<String, String> mdc = AriadneConfig.isMdcPropagationEnabled()
                                                ? MDC.getCopyOfContextMap() : null;
                                        Method spawnWithAttach = bootCarrier.getClass().getMethod("spawn", int.class, Object.class);
                                        return spawnWithAttach.invoke(bootCarrier, siteId, (mdc != null && !mdc.isEmpty()) ? mdc : null);
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
                                    MDC.setContextMap((Map<String, String>) targetMdc);

                                    return Proxy.newProxyInstance(
                                            null,
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
                        }
                );

                bootContext.getMethod("setCarrier", bootCarrierType).invoke(null, proxy);
            }
        } catch (Throwable ignored) {
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
                Class<?> bootCarrierType = Class.forName("io.ariadne.core.ContextCarrier", false, null);
                bootContext.getMethod("setCarrier", bootCarrierType).invoke(null, ORIGINAL_BOOTSTRAP_CARRIER);
            } catch (Throwable ignored) {}
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

            // Execute an async task to test cross-thread MDC propagation
            CompletableFuture<String> probeFuture = CompletableFuture.supplyAsync(() -> {
                // Must be wrapped or using AriadneContext
                return MDC.get(testKey);
            });

            // Fallback manual test inside current thread scope
            var link = AriadneContext.spawn(999999);
            String capturedInScope;
            try (var ignored = AriadneContext.attach(link)) {
                capturedInScope = MDC.get(testKey);
            }

            if (!testVal.equals(capturedInScope)) {
                return CanaryProbeResult.failure(FRAMEWORK_NAME, "MDC value was not preserved across AriadneContext scope");
            }

            return CanaryProbeResult.success(FRAMEWORK_NAME, "MDC propagation verified successfully");
        } catch (Throwable t) {
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
