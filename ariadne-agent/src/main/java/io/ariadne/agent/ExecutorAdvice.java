package io.ariadne.agent;

import java.util.concurrent.Callable;

import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice implementations intercepting {@link java.util.concurrent.Executor}
 * and {@link java.util.concurrent.ExecutorService} executions.
 * <p>
 * Uses a ThreadLocal guard to prevent double-wrapping when {@code submit()} internally
 * delegates to {@code execute()} (e.g., {@code AbstractExecutorService.submit(Callable)}
 * wraps in {@code FutureTask} then calls {@code execute(futureTask)}).
 * <p>
 * Note: Fields accessed by inlined Advice code MUST be public because they are accessed
 * from within transformed classes loaded in java.base.
 */
public final class ExecutorAdvice {

    public static final ThreadLocal<Boolean> SUBMIT_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ExecutorAdvice() {}

    public static class Execute {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                // Skip if we're inside a submit() call — submit already wrapped
                if (SUBMIT_ACTIVE.get()) {
                    return;
                }
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "Executor.execute");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class SubmitCallable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Callable<?> callable) {
            if (callable != null && !(callable instanceof AriadneCallable)) {
                SUBMIT_ACTIVE.set(Boolean.TRUE);
                int siteId = SiteRegistry.getOrRegister(callable.getClass(), "ExecutorService.submit(Callable)");
                callable = AriadneCallable.wrap(callable, AriadneContext.spawn(siteId));
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            SUBMIT_ACTIVE.set(Boolean.FALSE);
        }
    }

    public static class SubmitRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                SUBMIT_ACTIVE.set(Boolean.TRUE);
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "ExecutorService.submit(Runnable)");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            SUBMIT_ACTIVE.set(Boolean.FALSE);
        }
    }
}
