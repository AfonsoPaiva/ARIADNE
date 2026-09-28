package io.ariadne.agent;

import io.ariadne.core.Link;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Main Java Agent entry point for Ariadne.
 * <p>
 * Automatically attaches causality tracking to java.util.concurrent primitives
 * (CompletableFuture, Executor, ExecutorService) and boots reactive adapters if present.
 */
public final class AriadneAgent {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile ResettableClassFileTransformer TRANSFORMER;

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private AriadneAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static synchronized ResettableClassFileTransformer install(Instrumentation inst) {
        if (INSTALLED.get()) {
            return TRANSFORMER;
        }

        // 1. Ensure core classes are visible on bootstrap search path
        tryInjectBootstrapPath(inst);

        // 2. Initialize reactive adapters if respective frameworks are present
        initReactiveAdapters();

        // 3. Build ByteBuddy agent for java.util.concurrent
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .assureReadEdgeFromAndTo(inst, Link.class, CompletableFutureAdvice.class, ExecutorAdvice.class)
                .ignore(
                        nameStartsWith("net.bytebuddy.")
                                .or(nameStartsWith("io.ariadne.shaded."))
                                .or(nameStartsWith("jdk.internal."))
                                .or(nameStartsWith("sun."))
                                .and(not(named("java.util.concurrent.CompletableFuture")))
                                .and(not(hasSuperType(named("java.util.concurrent.Executor"))))
                )
                // CompletableFuture instrumentation
                .type(named("java.util.concurrent.CompletableFuture"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(CompletableFutureAdvice.SupplyAsync.class)
                                .on(named("supplyAsync").and(takesArguments(Supplier.class).or(takesArguments(Supplier.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.RunAsync.class)
                                .on(named("runAsync").and(takesArguments(Runnable.class).or(takesArguments(Runnable.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.ThenApplyAsync.class)
                                .on(named("thenApplyAsync").and(takesArguments(Function.class).or(takesArguments(Function.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.ThenAcceptAsync.class)
                                .on(named("thenAcceptAsync").and(takesArguments(Consumer.class).or(takesArguments(Consumer.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.ThenRunAsync.class)
                                .on(named("thenRunAsync").and(takesArguments(Runnable.class).or(takesArguments(Runnable.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.ThenComposeAsync.class)
                                .on(named("thenComposeAsync").and(takesArguments(Function.class).or(takesArguments(Function.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.HandleAsync.class)
                                .on(named("handleAsync").and(takesArguments(BiFunction.class).or(takesArguments(BiFunction.class, Executor.class)))))
                        .visit(Advice.to(CompletableFutureAdvice.WhenCompleteAsync.class)
                                .on(named("whenCompleteAsync").and(takesArguments(BiConsumer.class).or(takesArguments(BiConsumer.class, Executor.class)))))
                )
                // Executor & ExecutorService implementations
                .type(hasSuperType(named("java.util.concurrent.Executor"))
                        .and(not(isInterface()))
                        .and(not(nameStartsWith("java.util.concurrent.CompletableFuture"))))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ExecutorAdvice.Execute.class)
                                .on(named("execute").and(takesArguments(Runnable.class))))
                        .visit(Advice.to(ExecutorAdvice.SubmitCallable.class)
                                .on(named("submit").and(takesArguments(Callable.class))))
                        .visit(Advice.to(ExecutorAdvice.SubmitRunnable.class)
                                .on(named("submit").and(takesArguments(Runnable.class)
                                        .or(takesArguments(Runnable.class, Object.class)))))
                );

        TRANSFORMER = agentBuilder.installOn(inst);
        INSTALLED.set(true);
        return TRANSFORMER;
    }

    private static void tryInjectBootstrapPath(Instrumentation inst) {
        BootstrapInjector.inject(inst);
    }

    private static void initReactiveAdapters() {
        try {
            Class.forName("reactor.core.publisher.Flux");
            Class<?> reactorAdapter = Class.forName("io.ariadne.adapter.reactor.AriadneReactorAdapter");
            reactorAdapter.getMethod("install").invoke(null);
        } catch (Throwable ignored) {
            // Reactor not on classpath
        }

        try {
            Class.forName("io.reactivex.rxjava3.core.Observable");
            Class<?> rxAdapter = Class.forName("io.ariadne.adapter.rxjava.AriadneRxJavaAdapter");
            rxAdapter.getMethod("install").invoke(null);
        } catch (Throwable ignored) {
            // RxJava not on classpath
        }
    }
}
