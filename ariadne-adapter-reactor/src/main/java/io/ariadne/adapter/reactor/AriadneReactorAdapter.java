package io.ariadne.adapter.reactor;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.CallSiteMetadata;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.ariadne.core.UnsupportedFrameworkVersionException;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Project Reactor adapter providing zero-overhead causal propagation and lazy error reconstruction.
 * <p>
 * Integrates via Reactor's official hook points:
 * <ul>
 *   <li>{@link Schedulers#onScheduleHook(String, Function)} for cross-thread hop propagation.</li>
 *   <li>{@link Hooks#onOperatorError(String, BiFunction)} for lazy causal enrichment on reactive error signals.</li>
 * </ul>
 * <p>
 * Includes an automated Canary Probe self-test to detect silent hook bypasses upon installation.
 */
public final class AriadneReactorAdapter {

    public static final String HOOK_KEY = "io.ariadne";
    public static final String MINIMUM_REACTOR_VERSION = "3.1.0";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile CanaryProbeResult HEALTH_STATUS =
            CanaryProbeResult.failure("Project Reactor", "Adapter is not installed");

    private AriadneReactorAdapter() {}

    /**
     * Verifies framework compatibility, registers call sites, installs non-destructive hooks,
     * and runs a Canary Probe self-test to verify hook interception.
     *
     * @throws UnsupportedFrameworkVersionException if the runtime Reactor version lacks required hook APIs.
     */
    public static synchronized void install() {
        if (INSTALLED.get()) {
            return;
        }

        verifyCompatibility();

        // 1. Thread boundary propagation hook
        Schedulers.onScheduleHook(HOOK_KEY, runnable -> {
            int siteId = SiteRegistry.captureCallerSiteId(0, "Reactor Scheduler Dispatch");
            Link hop = AriadneContext.spawn(siteId);
            return AriadneRunnable.wrap(runnable, hop);
        });

        // 2. Operator error signal hook (reconstructs causality when an operator fails asynchronously)
        Hooks.onOperatorError(HOOK_KEY, (throwable, data) -> {
            Link current = AriadneContext.current();
            if (throwable != null && current != null) {
                AriadneReconstructor.enrich(throwable, current);
            }
            return throwable;
        });

        INSTALLED.set(true);

        // 3. Canary Probe: Execute self-test to ensure the hook is actually intercepted by the framework
        HEALTH_STATUS = runCanaryProbe();
        if (!HEALTH_STATUS.isHealthy()) {
            System.err.println("[Ariadne WARNING] " + HEALTH_STATUS.message());
        }
    }

    /**
     * Uninstalls Ariadne hooks and restores Reactor scheduler and operator states.
     */
    public static synchronized void uninstall() {
        if (!INSTALLED.get()) {
            return;
        }

        Schedulers.resetOnScheduleHook(HOOK_KEY);
        Hooks.resetOnOperatorError(HOOK_KEY);
        HEALTH_STATUS = CanaryProbeResult.failure("Project Reactor", "Adapter was uninstalled");
        INSTALLED.set(false);
    }

    /**
     * Checks if the adapter is actively installed.
     */
    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    /**
     * Returns true if the Canary Probe successfully verified active causality propagation.
     */
    public static boolean isHealthy() {
        return HEALTH_STATUS != null && HEALTH_STATUS.isHealthy();
    }

    /**
     * Returns the detailed diagnostic status of the Canary Probe.
     */
    public static CanaryProbeResult healthStatus() {
        return HEALTH_STATUS;
    }

    /**
     * Executes an end-to-end canary probe on a dedicated scheduler worker.
     * Verifies that the schedule hook intercepted the task, populated a child link, and maintained context.
     */
    public static CanaryProbeResult runCanaryProbe() {
        if (!INSTALLED.get()) {
            return CanaryProbeResult.failure("Project Reactor", "Cannot run probe: adapter is not installed.");
        }

        int probeSite = SiteRegistry.register(new CallSiteMetadata(
                "io.ariadne.adapter.reactor.CanaryProbe", "probe", "CanaryProbe.java", 1, "Canary Probe"
        ));
        Link probeOrigin = new Link(null, probeSite, Thread.currentThread().threadId());

        AtomicBoolean intercepted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        try (AriadneContext.Scope ignored = AriadneContext.attach(probeOrigin)) {
            Schedulers.single().schedule(() -> {
                try {
                    Link current = AriadneContext.current();
                    if (current != null && current.parent == probeOrigin) {
                        intercepted.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            });

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            if (!completed) {
                return CanaryProbeResult.failure("Project Reactor", "Canary task timed out before scheduler execution.");
            }
            if (!intercepted.get()) {
                return CanaryProbeResult.failure("Project Reactor", "Schedulers.onScheduleHook did not intercept task execution. Schedulers may be bypassed.");
            }

            return CanaryProbeResult.success("Project Reactor");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CanaryProbeResult.failure("Project Reactor", "Canary probe interrupted", e);
        } catch (Throwable t) {
            return CanaryProbeResult.failure("Project Reactor", "Canary probe encountered error: " + t.getMessage(), t);
        }
    }

    /**
     * Validates that Project Reactor provides the required composable hook methods.
     */
    static void verifyCompatibility() {
        try {
            Class<?> schedulersClass = Class.forName("reactor.core.scheduler.Schedulers");
            Method onScheduleHookMethod = schedulersClass.getMethod("onScheduleHook", String.class, Function.class);
            if (onScheduleHookMethod == null) {
                throw new NoSuchMethodException("Schedulers.onScheduleHook(String, Function)");
            }

            Class<?> hooksClass = Class.forName("reactor.core.publisher.Hooks");
            Method onOperatorErrorMethod = hooksClass.getMethod("onOperatorError", String.class, BiFunction.class);
            if (onOperatorErrorMethod == null) {
                throw new NoSuchMethodException("Hooks.onOperatorError(String, BiFunction)");
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new UnsupportedFrameworkVersionException(
                    "Project Reactor",
                    MINIMUM_REACTOR_VERSION,
                    "Required composable hook methods not found: " + e.getMessage()
            );
        }
    }
}
