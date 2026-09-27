package io.ariadne.adapter.rxjava;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.ariadne.core.UnsupportedFrameworkVersionException;
import io.reactivex.rxjava3.functions.BiFunction;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RxJava 3 adapter providing zero-overhead causal propagation and lazy error reconstruction.
 * <p>
 * Complies strictly with hook composition: reads existing hooks, chains Ariadne's wrapper,
 * and restores previous state on uninstall without corrupting co-existing telemetry agents.
 */
public final class AriadneRxJavaAdapter {

    public static final String MINIMUM_RXJAVA_VERSION = "3.0.0";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Stored previous handlers for full composition and safe reversal
    private static volatile Function<? super Runnable, ? extends Runnable> PREV_SCHEDULE_HANDLER;
    @SuppressWarnings("rawtypes")
    private static volatile BiFunction PREV_OBSERVABLE_SUB;
    @SuppressWarnings("rawtypes")
    private static volatile BiFunction PREV_SINGLE_SUB;
    @SuppressWarnings("rawtypes")
    private static volatile BiFunction PREV_MAYBE_SUB;
    @SuppressWarnings("rawtypes")
    private static volatile BiFunction PREV_COMPLETABLE_SUB;
    @SuppressWarnings("rawtypes")
    private static volatile BiFunction PREV_FLOWABLE_SUB;

    private AriadneRxJavaAdapter() {}

    /**
     * Verifies framework compatibility, saves existing handlers, and installs the chained handlers.
     *
     * @throws UnsupportedFrameworkVersionException if the runtime RxJava version lacks required hook APIs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void install() {
        if (INSTALLED.get()) {
            return;
        }

        verifyCompatibility();

        // 1. Thread boundary propagation: Schedule handler
        PREV_SCHEDULE_HANDLER = RxJavaPlugins.getScheduleHandler();
        final Function<? super Runnable, ? extends Runnable> existingSchedule = PREV_SCHEDULE_HANDLER;
        RxJavaPlugins.setScheduleHandler(runnable -> {
            int siteId = SiteRegistry.captureCallerSiteId(0, "RxJava Schedule Dispatch");
            Link hop = AriadneContext.spawn(siteId);
            Runnable ariadneWrapped = AriadneRunnable.wrap(runnable, hop);

            if (existingSchedule != null) {
                return existingSchedule.apply(ariadneWrapped);
            }
            return ariadneWrapped;
        });

        // 2. Reactive error propagation: Observer wrappers
        PREV_OBSERVABLE_SUB = RxJavaPlugins.getOnObservableSubscribe();
        final BiFunction existingObs = PREV_OBSERVABLE_SUB;
        RxJavaPlugins.setOnObservableSubscribe((observable, observer) -> {
            var downstream = existingObs != null ? existingObs.apply(observable, observer) : observer;
            return AriadneRxObservers.wrap((io.reactivex.rxjava3.core.Observer) downstream);
        });

        PREV_SINGLE_SUB = RxJavaPlugins.getOnSingleSubscribe();
        final BiFunction existingSingle = PREV_SINGLE_SUB;
        RxJavaPlugins.setOnSingleSubscribe((single, observer) -> {
            var downstream = existingSingle != null ? existingSingle.apply(single, observer) : observer;
            return AriadneRxObservers.wrap((io.reactivex.rxjava3.core.SingleObserver) downstream);
        });

        PREV_MAYBE_SUB = RxJavaPlugins.getOnMaybeSubscribe();
        final BiFunction existingMaybe = PREV_MAYBE_SUB;
        RxJavaPlugins.setOnMaybeSubscribe((maybe, observer) -> {
            var downstream = existingMaybe != null ? existingMaybe.apply(maybe, observer) : observer;
            return AriadneRxObservers.wrap((io.reactivex.rxjava3.core.MaybeObserver) downstream);
        });

        PREV_COMPLETABLE_SUB = RxJavaPlugins.getOnCompletableSubscribe();
        final BiFunction existingComp = PREV_COMPLETABLE_SUB;
        RxJavaPlugins.setOnCompletableSubscribe((completable, observer) -> {
            var downstream = existingComp != null ? existingComp.apply(completable, observer) : observer;
            return AriadneRxObservers.wrap((io.reactivex.rxjava3.core.CompletableObserver) downstream);
        });

        PREV_FLOWABLE_SUB = RxJavaPlugins.getOnFlowableSubscribe();
        final BiFunction existingFlow = PREV_FLOWABLE_SUB;
        RxJavaPlugins.setOnFlowableSubscribe((flowable, subscriber) -> {
            var downstream = existingFlow != null ? existingFlow.apply(flowable, subscriber) : subscriber;
            return AriadneRxObservers.wrap((org.reactivestreams.Subscriber) downstream);
        });

        INSTALLED.set(true);
    }

    /**
     * Restores all hooks that were present before Ariadne was installed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void uninstall() {
        if (!INSTALLED.get()) {
            return;
        }

        RxJavaPlugins.setScheduleHandler(PREV_SCHEDULE_HANDLER);
        RxJavaPlugins.setOnObservableSubscribe((BiFunction) PREV_OBSERVABLE_SUB);
        RxJavaPlugins.setOnSingleSubscribe((BiFunction) PREV_SINGLE_SUB);
        RxJavaPlugins.setOnMaybeSubscribe((BiFunction) PREV_MAYBE_SUB);
        RxJavaPlugins.setOnCompletableSubscribe((BiFunction) PREV_COMPLETABLE_SUB);
        RxJavaPlugins.setOnFlowableSubscribe((BiFunction) PREV_FLOWABLE_SUB);

        PREV_SCHEDULE_HANDLER = null;
        PREV_OBSERVABLE_SUB = null;
        PREV_SINGLE_SUB = null;
        PREV_MAYBE_SUB = null;
        PREV_COMPLETABLE_SUB = null;
        PREV_FLOWABLE_SUB = null;

        INSTALLED.set(false);
    }

    /**
     * Checks if the adapter is actively installed.
     */
    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    /**
     * Validates that RxJava 3 provides the required hook APIs.
     */
    static void verifyCompatibility() {
        try {
            Class<?> pluginsClass = Class.forName("io.reactivex.rxjava3.plugins.RxJavaPlugins");
            Method getMethod = pluginsClass.getMethod("getScheduleHandler");
            Method setMethod = pluginsClass.getMethod("setScheduleHandler", Function.class);
            if (getMethod == null || setMethod == null) {
                throw new NoSuchMethodException("RxJavaPlugins.getScheduleHandler / setScheduleHandler");
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new UnsupportedFrameworkVersionException(
                    "RxJava 3",
                    MINIMUM_RXJAVA_VERSION,
                    "Required schedule handler methods not found: " + e.getMessage()
            );
        }
    }
}
