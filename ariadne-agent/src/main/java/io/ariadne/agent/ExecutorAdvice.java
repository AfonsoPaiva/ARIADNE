package io.ariadne.agent;

import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.Callable;

/**
 * ByteBuddy advice implementations intercepting {@link java.util.concurrent.Executor}
 * and {@link java.util.concurrent.ExecutorService} executions.
 */
public final class ExecutorAdvice {

    private ExecutorAdvice() {}

    public static class Execute {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "Executor.execute");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class SubmitCallable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Callable<?> callable) {
            if (callable != null && !(callable instanceof AriadneCallable)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "ExecutorService.submit(Callable)");
                callable = AriadneCallable.wrap(callable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class SubmitRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "ExecutorService.submit(Runnable)");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }
}
