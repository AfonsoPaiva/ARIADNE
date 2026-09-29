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
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Supplier<?> supplier) {
            if (supplier != null && !(supplier instanceof AriadneSupplier)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.supplyAsync");
                    cachedSiteId = siteId;
                }
                supplier = AriadneSupplier.wrap(supplier, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class RunAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.runAsync");
                    cachedSiteId = siteId;
                }
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenApplyAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneFunction)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.thenApplyAsync");
                    cachedSiteId = siteId;
                }
                fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenAcceptAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Consumer<?> consumer) {
            if (consumer != null && !(consumer instanceof AriadneConsumer)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.thenAcceptAsync");
                    cachedSiteId = siteId;
                }
                consumer = AriadneConsumer.wrap(consumer, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenRunAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            if (runnable != null && !(runnable instanceof AriadneRunnable)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.thenRunAsync");
                    cachedSiteId = siteId;
                }
                runnable = AriadneRunnable.wrap(runnable, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class ThenComposeAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Function<?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneFunction)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.thenComposeAsync");
                    cachedSiteId = siteId;
                }
                fn = AriadneFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class HandleAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiFunction<?, ?, ?> fn) {
            if (fn != null && !(fn instanceof AriadneBiFunction)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.handleAsync");
                    cachedSiteId = siteId;
                }
                fn = AriadneBiFunction.wrap(fn, AriadneContext.spawn(siteId));
            }
        }
    }

    public static class WhenCompleteAsync {
        public static volatile int cachedSiteId;

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(value = 0, readOnly = false) BiConsumer<?, ?> consumer) {
            if (consumer != null && !(consumer instanceof AriadneBiConsumer)) {
                int siteId = cachedSiteId;
                if (siteId == 0) {
                    siteId = SiteRegistry.getOrRegister("CompletableFuture.whenCompleteAsync");
                    cachedSiteId = siteId;
                }
                consumer = AriadneBiConsumer.wrap(consumer, AriadneContext.spawn(siteId));
            }
        }
    }
}
