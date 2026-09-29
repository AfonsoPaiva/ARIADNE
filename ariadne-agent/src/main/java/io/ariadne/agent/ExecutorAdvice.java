package io.ariadne.agent;

import java.util.concurrent.Callable;

import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice implementations intercepting {@link java.util.concurrent.Executor},
 * {@link java.util.concurrent.ExecutorService}, and {@link java.util.concurrent.ScheduledExecutorService} executions.
 * <p>
 * Uses a ThreadLocal depth counter to prevent double-wrapping when {@code submit()} internally
 * delegates to {@code execute()} (e.g., {@code AbstractExecutorService.submit(Callable)}
 * wraps in {@code FutureTask} then calls {@code execute(futureTask)}).
 * <p>
 * A depth counter ensures that nested submits (e.g., when tasks execute synchronously under
 * {@code ThreadPoolExecutor.CallerRunsPolicy} or submit child tasks) do not prematurely reset
 * the guard flag on exit of an inner task.
 * <p>
 * Note: Fields accessed by inlined Advice code MUST be public because they are accessed
 * from within transformed classes loaded in java.base.
 */
public final class ExecutorAdvice {

    public static final ThreadLocal<Integer> SUBMIT_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ExecutorAdvice() {}

    public static class Execute {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                // Skip if we're inside a submit() call — submit already wrapped
                if (SUBMIT_DEPTH.get() > 0) {
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
            SUBMIT_DEPTH.set(SUBMIT_DEPTH.get() + 1);
            if (callable != null && !(callable instanceof AriadneCallable)) {
                int siteId = SiteRegistry.getOrRegister(callable.getClass(), "ExecutorService.submit(Callable)");
                callable = AriadneCallable.wrap(callable, AriadneContext.spawn(siteId));
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            int depth = SUBMIT_DEPTH.get() - 1;
            if (depth <= 0) {
                SUBMIT_DEPTH.remove();
            } else {
                SUBMIT_DEPTH.set(depth);
            }
        }
    }

    public static class SubmitRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            SUBMIT_DEPTH.set(SUBMIT_DEPTH.get() + 1);
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "ExecutorService.submit(Runnable)");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            int depth = SUBMIT_DEPTH.get() - 1;
            if (depth <= 0) {
                SUBMIT_DEPTH.remove();
            } else {
                SUBMIT_DEPTH.set(depth);
            }
        }
    }

    public static class ScheduleRunnable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "ScheduledExecutorService.schedule(Runnable)");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ScheduleCallable {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Callable<?> callable) {
            if (callable != null && !(callable instanceof AriadneCallable)) {
                int siteId = SiteRegistry.getOrRegister(callable.getClass(), "ScheduledExecutorService.schedule(Callable)");
                callable = AriadneCallable.wrap(callable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ScheduleAtFixedRate {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "ScheduledExecutorService.scheduleAtFixedRate");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ScheduleWithFixedDelay {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "ScheduledExecutorService.scheduleWithFixedDelay");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }
}
