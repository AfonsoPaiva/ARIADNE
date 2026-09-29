package io.ariadne.agent;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.ariadne.core.AriadneBiConsumer;
import io.ariadne.core.AriadneBiFunction;
import io.ariadne.core.AriadneConsumer;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneFunction;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.AriadneSupplier;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice implementations intercepting {@link java.util.concurrent.CompletableFuture} methods.
 * <p>
 * Ensures that all functional continuations (Supplier, Function, Consumer, etc.) are wrapped at entry,
 * preserving causality across {@link java.util.concurrent.ForkJoinPool#commonPool()} and custom executors.
 * <p>
 * Each advice class caches its siteId after first invocation via {@link SiteRegistry#getOrRegister(String)},
 * avoiding StackWalker overhead on every dispatch (~1.7 µs → ~5 ns).
 * <p>
 * Note: Fields accessed by inlined Advice code MUST be public because they are accessed
 * from within transformed classes loaded in java.base.
 */
public final class CompletableFutureAdvice {

    private CompletableFutureAdvice() {}

    public static class SupplyAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Supplier<?> supplier) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (supplier != null && !io.ariadne.core.AriadneConfig.isClassExcluded(supplier.getClass().getName()) && !(supplier instanceof AriadneSupplier)) {
                    int siteId = SiteRegistry.getOrRegister(supplier.getClass(), "CompletableFuture.supplyAsync");
                    supplier = AriadneSupplier.wrap(supplier, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class RunAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (runnable != null && !io.ariadne.core.AriadneConfig.isClassExcluded(runnable.getClass().getName()) && !(runnable instanceof AriadneRunnable)) {
                    int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "CompletableFuture.runAsync");
                    runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class ThenApplyAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (fn != null && !io.ariadne.core.AriadneConfig.isClassExcluded(fn.getClass().getName()) && !(fn instanceof AriadneFunction)) {
                    int siteId = SiteRegistry.getOrRegister(fn.getClass(), "CompletableFuture.thenApplyAsync");
                    fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class ThenAcceptAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Consumer<?> consumer) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (consumer != null && !io.ariadne.core.AriadneConfig.isClassExcluded(consumer.getClass().getName()) && !(consumer instanceof AriadneConsumer)) {
                    int siteId = SiteRegistry.getOrRegister(consumer.getClass(), "CompletableFuture.thenAcceptAsync");
                    consumer = AriadneConsumer.wrap(consumer, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class ThenRunAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (runnable != null && !io.ariadne.core.AriadneConfig.isClassExcluded(runnable.getClass().getName()) && !(runnable instanceof AriadneRunnable)) {
                    int siteId = SiteRegistry.getOrRegister(runnable.getClass(), "CompletableFuture.thenRunAsync");
                    runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class ThenComposeAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (fn != null && !io.ariadne.core.AriadneConfig.isClassExcluded(fn.getClass().getName()) && !(fn instanceof AriadneFunction)) {
                    int siteId = SiteRegistry.getOrRegister(fn.getClass(), "CompletableFuture.thenComposeAsync");
                    fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class HandleAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiFunction<?, ?, ?> fn) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (fn != null && !io.ariadne.core.AriadneConfig.isClassExcluded(fn.getClass().getName()) && !(fn instanceof AriadneBiFunction)) {
                    int siteId = SiteRegistry.getOrRegister(fn.getClass(), "CompletableFuture.handleAsync");
                    fn = AriadneBiFunction.wrap(fn, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static class WhenCompleteAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiConsumer<?, ?> consumer) {
            try {
                if (!io.ariadne.core.AriadneConfig.isEnabled()) {
                    return;
                }
                if (consumer != null && !io.ariadne.core.AriadneConfig.isClassExcluded(consumer.getClass().getName()) && !(consumer instanceof AriadneBiConsumer)) {
                    int siteId = SiteRegistry.getOrRegister(consumer.getClass(), "CompletableFuture.whenCompleteAsync");
                    consumer = AriadneBiConsumer.wrap(consumer, AriadneContext.spawn(siteId));
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
