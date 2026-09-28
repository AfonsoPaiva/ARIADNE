package io.ariadne.agent;

import io.ariadne.core.AriadneBiConsumer;
import io.ariadne.core.AriadneBiFunction;
import io.ariadne.core.AriadneConsumer;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneFunction;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.AriadneSupplier;
import io.ariadne.core.SiteRegistry;
import net.bytebuddy.asm.Advice;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ByteBuddy advice implementations intercepting {@link java.util.concurrent.CompletableFuture} methods.
 * <p>
 * Ensures that all functional continuations (Supplier, Function, Consumer, etc.) are wrapped at entry,
 * preserving causality across {@link java.util.concurrent.ForkJoinPool#commonPool()} and custom executors.
 */
public final class CompletableFutureAdvice {

    private CompletableFutureAdvice() {}

    public static class SupplyAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Supplier<?> supplier) {
            if (supplier != null && !(supplier instanceof AriadneSupplier)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.supplyAsync");
                supplier = AriadneSupplier.wrap(supplier, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class RunAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.runAsync");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenApplyAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneFunction)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.thenApplyAsync");
                fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenAcceptAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Consumer<?> consumer) {
            if (consumer != null && !(consumer instanceof AriadneConsumer)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.thenAcceptAsync");
                consumer = AriadneConsumer.wrap(consumer, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenRunAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.thenRunAsync");
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenComposeAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneFunction)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.thenComposeAsync");
                fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class HandleAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiFunction<?, ?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneBiFunction)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.handleAsync");
                fn = AriadneBiFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class WhenCompleteAsync {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiConsumer<?, ?> consumer) {
            if (consumer != null && !(consumer instanceof AriadneBiConsumer)) {
                int siteId = SiteRegistry.captureCallerSiteId(1, "CompletableFuture.whenCompleteAsync");
                consumer = AriadneBiConsumer.wrap(consumer, AriadneContext.spawn(siteId));
            }
        }
    }
}
