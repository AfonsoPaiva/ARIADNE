package io.ariadne.adapter.reactor;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.ariadne.core.UnsupportedFrameworkVersionException;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
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
 * Complies with hook composition standards by using a unique key ("io.ariadne"), ensuring that
 * existing instrumentation (Micrometer, Brave, OpenTelemetry) is never overwritten.
 */
public final class AriadneReactorAdapter {

    public static final String HOOK_KEY = "io.ariadne";
    public static final String MINIMUM_REACTOR_VERSION = "3.1.0";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AriadneReactorAdapter() {}

    /**
     * Verifies framework compatibility, registers call sites, and installs non-destructive hooks.
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
        INSTALLED.set(false);
    }

    /**
     * Checks if the adapter is actively installed.
     */
    public static boolean isInstalled() {
        return INSTALLED.get();
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
