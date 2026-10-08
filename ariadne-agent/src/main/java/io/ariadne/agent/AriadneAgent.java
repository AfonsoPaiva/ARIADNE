package io.ariadne.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.ariadne.core.Link;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Main Java Agent entry point for Ariadne.
 * <p>
 * Automatically attaches causality tracking to java.util.concurrent primitives
 * (CompletableFuture, Executor, ExecutorService) and boots reactive adapters if present.
 */
public final class AriadneAgent {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    @SuppressWarnings("unused")
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

    public static synchronized void install(Instrumentation inst) {
        if (INSTALLED.get()) {
            return;
        }

        if (!io.ariadne.core.AriadneConfig.isEnabled()) {
            return;
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
                .assureReadEdgeFromAndTo(inst, Link.class, CompletableFutureAdvice.class, ExecutorAdvice.class, ThreadAdvice.class)
                .ignore(
                        nameStartsWith("net.bytebuddy.")
                                .or(nameStartsWith("io.ariadne."))
                                .or(nameStartsWith("jdk.internal."))
                                .or(nameStartsWith("jdk.proxy"))
                                .or(nameStartsWith("sun."))
                                .or(nameStartsWith("org.springframework.cglib."))
                                .or(nameStartsWith("org.objectweb.asm."))
                                .or(nameStartsWith("ch.qos.logback."))
                                .or(nameStartsWith("org.slf4j."))
                                .or(target -> io.ariadne.core.AriadneConfig.isClassExcluded(target.getActualName()))
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
                // Executor & ExecutorService & ScheduledExecutorService implementations
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
                        .visit(Advice.to(ExecutorAdvice.ScheduleRunnable.class)
                                .on(named("schedule").and(takesArguments(Runnable.class, long.class, TimeUnit.class))))
                        .visit(Advice.to(ExecutorAdvice.ScheduleCallable.class)
                                .on(named("schedule").and(takesArguments(Callable.class, long.class, TimeUnit.class))))
                        .visit(Advice.to(ExecutorAdvice.ScheduleAtFixedRate.class)
                                .on(named("scheduleAtFixedRate").and(takesArguments(Runnable.class, long.class, long.class, TimeUnit.class))))
                        .visit(Advice.to(ExecutorAdvice.ScheduleWithFixedDelay.class)
                                .on(named("scheduleWithFixedDelay").and(takesArguments(Runnable.class, long.class, long.class, TimeUnit.class))))
                )
                // Virtual threads & Thread builders
                .type(hasSuperType(named("java.lang.Thread$Builder")).and(not(isInterface())))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ThreadAdvice.ThreadBuilderStart.class)
                                .on(named("start").and(takesArguments(Runnable.class))))
                        .visit(Advice.to(ThreadAdvice.ThreadBuilderStart.class)
                                .on(named("unstarted").and(takesArguments(Runnable.class))))
                )
                .type(named("java.lang.Thread"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ThreadAdvice.StartVirtualThread.class)
                                .on(named("startVirtualThread").and(takesArguments(Runnable.class))))
                )
                .type(named("java.lang.ThreadBuilders"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(ThreadAdvice.NewVirtualThread.class)
                                .on(named("newVirtualThread").and(takesArguments(Executor.class, String.class, int.class, Runnable.class))))
                );

        TRANSFORMER = agentBuilder.installOn(inst);
        INSTALLED.set(true);

        io.ariadne.core.AriadneManagement.registerMBean();
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

        try {
            Class.forName("org.slf4j.MDC");
            Class<?> mdcAdapter = Class.forName("io.ariadne.adapter.mdc.AriadneMdcAdapter");
            mdcAdapter.getMethod("install").invoke(null);
        } catch (Throwable ignored) {
            // SLF4J MDC not on classpath
        }

        try {
            Class<?> otelBridge = Class.forName("io.ariadne.adapter.otel.AriadneOtelBridge");
            otelBridge.getMethod("installIfEnabled").invoke(null);
        } catch (Throwable ignored) {
            // OTel bridge not on classpath or not enabled
        }
    }
}
