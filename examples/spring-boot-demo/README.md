# Ariadne Spring Boot Demo (@Async & Project Reactor)

This demo demonstrates how Ariadne eliminates asynchronous blind spots in a Spring Boot application using:
1. **Spring `@Async`** backed by `ThreadPoolTaskExecutor` calling `CompletableFuture.supplyAsync()`
2. **Project Reactor / WebFlux** pipelines dispatching across `boundedElastic()` and `parallel()` schedulers
3. **SLF4J MDC context propagation** preserving correlation IDs (`traceId`, `userId`) across thread handoffs

---

## How to Build

From the repository root:
```bash
# 1. Package Ariadne parent and all modules (including agent)
mvn clean package -DskipTests

# 2. Package the demo application (produces shaded runnable fat jar)
mvn -f examples/spring-boot-demo/pom.xml package -DskipTests
```

---

## How to Run & Compare (Before vs After)

### Scenario A: Without Ariadne Agent (Default JVM Behavior)

```bash
java -jar examples/spring-boot-demo/target/spring-boot-demo-0.1.0-alpha.2.jar
```

**Real Output Observed:**
```text
>>> SCENARIO 1: Spring @Async + CompletableFuture Multi-Hop Exception
OrderController calls OrderService.placeOrderAsync() -> PaymentService.processPayment()

Captured Exception for [Spring @Async Flow]:
Exception Type: java.lang.IllegalStateException
Message:        Payment gateway connection timeout [orderId=ORD-2026-999]

>>> [BEFORE: DEFAULT JVM TRUNCATED TRACE — BLIND SPOT]
  Standard JVM Stack Trace (First 5 frames):
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1781)
    at java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:511)
    at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
  --> Notice: The caller method (OrderService.placeOrderAsync) and HTTP request context are COMPLETELY LOST.
      The stack trace starts at the background worker thread pool.
```

---

### Scenario B: With Ariadne Agent (Default `CLASS` Mode — Near-Zero Overhead)

```bash
java -javaagent:ariadne-agent/target/ariadne-agent-0.1.0-alpha.2.jar \
     -jar examples/spring-boot-demo/target/spring-boot-demo-0.1.0-alpha.2.jar
```

**Real Output Observed (Default `CLASS` Mode with `ClassValue` cache):**
```text
>>> SCENARIO 1: Spring @Async + CompletableFuture Multi-Hop Exception
OrderController calls OrderService.placeOrderAsync() -> PaymentService.processPayment()

Captured Exception for [Spring @Async Flow]:
Exception Type: java.lang.IllegalStateException
Message:        Payment gateway connection timeout [orderId=ORD-2026-999]

>>> [AFTER: ARIADNE RECONSTRUCTION DETECTED]
  Asynchronous execution path (2 hops)
    at io.ariadne.demo.PaymentService.lambda(PaymentService.java)
    at io.ariadne.agent.Executor.execute(Unknown Source)
  --> Causality across asynchronous thread boundaries successfully preserved!

--------------------------------------------------------------------------------

>>> SCENARIO 2: Project Reactor / WebFlux Scheduler Exception
Pipeline: publishOn(boundedElastic) -> map() -> publishOn(parallel) -> error

Captured Exception for [Reactor / WebFlux Flow]:
Exception Type: java.lang.RuntimeException
Message:        Inventory deduction failed for: validated-ORD-2026-888

>>> [AFTER: ARIADNE RECONSTRUCTION DETECTED]
  Asynchronous execution path (2 hops) [Context: {traceId=req-89a1f4b2, userId=afonso-paiva}]
    at io.ariadne.agent.Reactor Scheduler Dispatch(Unknown Source)
    at io.ariadne.agent.Reactor Scheduler Dispatch(Unknown Source)
  --> Causality across asynchronous thread boundaries successfully preserved!
```

---

### Scenario C: With Ariadne Agent in `FULL` Mode (Exact Method & Line Number)

When detailed source line diagnostics are required (e.g., in staging or debugging), set `-Dariadne.callsite.mode=full`:

```bash
java -javaagent:ariadne-agent/target/ariadne-agent-0.1.0-alpha.2.jar \
     -Dariadne.callsite.mode=full \
     -jar examples/spring-boot-demo/target/spring-boot-demo-0.1.0-alpha.2.jar
```

**Real Output Observed (`FULL` Mode with exact StackWalker line capture):**
```text
>>> [AFTER: ARIADNE RECONSTRUCTION DETECTED]
  Asynchronous execution path (2 hops)
    at io.ariadne.demo.PaymentService.processPayment(PaymentService.java:16)
    at org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1.execute(ThreadPoolTaskExecutor.java:295)
  --> Causality across asynchronous thread boundaries successfully preserved with exact method and line numbers!
```

---

## Call Site Resolution Modes & Benchmark Costs

Ariadne supports 3 call site resolution modes configured via `-Dariadne.callsite.mode=<mode>` or `ARIADNE_CALLSITE_MODE=<mode>` (and dynamically via JMX MBean `io.ariadne:type=Ariadne`):

| Mode | Configuration Flag | Resolution Mechanism | JMH Latency | GC Allocation | Description & Use Case |
| :--- | :--- | :--- | :---: | :---: | :--- |
| **`CLASS`** *(Default)* | `-Dariadne.callsite.mode=class` | `ClassValue<Integer>` cache | **~5.7 ns/op** | **0.0 B/op** | Resolves dispatching class (`PaymentService.lambda(PaymentService.java)`) at zero allocation. Recommended for production high-throughput services. |
| **`SAMPLED:N`** | `-Dariadne.callsite.mode=sampled:100` | Counter + `StackWalker` (1 in N) | **~39.1 ns/op** | **~9.7 B/op** | Samples 1 in every N dispatches with full line capture, falling back to `ClassValue` for the remaining 99%. Ideal for low-overhead sampling. |
| **`FULL`** | `-Dariadne.callsite.mode=full` | `StackWalker` on every dispatch | **~1.42 µs/op** | **~960 B/op** | Captures exact method name and source line (`PaymentService.processPayment(PaymentService.java:16)`). Recommended for staging, canary testing, or debugging. |

