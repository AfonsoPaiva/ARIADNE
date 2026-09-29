package io.ariadne.agent;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice intercepting virtual thread and thread builder creations.
 * <p>
 * Intercepts {@link Thread#startVirtualThread(Runnable)}, {@code Thread.Builder#start(Runnable)},
 * {@code Thread.Builder#unstarted(Runnable)}, and {@code ThreadBuilders#newVirtualThread}
 * to ensure that tasks dispatched to virtual threads inherit active causal context.
 */
public final class ThreadAdvice {

    private ThreadAdvice() {}

    public static class StartVirtualThread {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (runnable != null && !io.ariadne.core.AriadneConfig.isClassExcluded(runnable.getClass().getName()) && !(runnable instanceof AriadneRunnable)) {
                    int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "Thread.startVirtualThread");
                    runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class ThreadBuilderStart {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (runnable != null && !io.ariadne.core.AriadneConfig.isClassExcluded(runnable.getClass().getName()) && !(runnable instanceof AriadneRunnable)) {
                    int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "Thread.ofVirtual");
                    runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class NewVirtualThread {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 3, readOnly = false) Runnable runnable) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (runnable != null && !io.ariadne.core.AriadneConfig.isClassExcluded(runnable.getClass().getName()) && !(runnable instanceof AriadneRunnable)) {
                    int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "Thread.ofVirtual");
                    runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
