<p align="center">
  <img src="docs/Images/logo-vertical-dark-background.svg" alt="Ariadne Logo" width="320">
</p>

<h3 align="center">Low-overhead, production-safe asynchronous causality stack trace reconstruction for the JVM</h3>

<p align="center">
  <a href="https://github.com/AfonsoPaiva/ARIADNE/releases/tag/v0.1.0-beta.1"><img src="https://img.shields.io/badge/Release-v0.1.0--beta.1-911010?style=for-the-badge" alt="Release"></a>
  <img src="https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Byte%20Buddy-1.14.12-2962FF?style=for-the-badge" alt="Byte Buddy">
  <img src="https://img.shields.io/badge/License-Apache%202.0-911010?style=for-the-badge" alt="License">
</p>

<p align="center">
  <a href="https://afonsopaiva.github.io/ARIADNE/wiki.html">
    <img src="https://img.shields.io/badge/📖_Explore-Technical_Wiki_%26_Docs-911010?style=for-the-badge" alt="Technical Wiki">
  </a>
  <a href="https://github.com/AfonsoPaiva/ARIADNE/releases">
    <img src="https://img.shields.io/badge/📦_Download-Release_JARs-2A2727?style=for-the-badge" alt="Releases">
  </a>
</p>

---

## What Ariadne Does

When an exception occurs on a worker thread in Java, standard JVM stack traces terminate at `ThreadPoolExecutor.runWorker()`. The originating caller, request context, and asynchronous dispatch chain are lost.

**Ariadne solves this by reconstructing the full causal chain across thread boundaries without manual code changes.**

```
[DEFAULT JVM TRUNCATED TRACE]
java.lang.IllegalStateException: Payment gateway timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
    # BLIND SPOT: Originating caller method and HTTP request parameters are permanently lost.

[WITH ARIADNE]
java.lang.IllegalStateException: Payment gateway timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {orderId=ORD-2026-999}]
        at io.ariadne.demo.PaymentService.lambda(PaymentService.java)
        at io.ariadne.demo.OrderService.placeOrderAsync(OrderService.java:29)
```

### Why it's fast (Near-Zero Overhead)
- **Normal Path (99.9%+):** Allocates a single immutable 40-byte pointer node (`Link`) in thread-local memory. No stack traces or Throwables are captured during task submission (~5.8 ns).
- **Failure Path Only:** Only when an unhandled exception is thrown does Ariadne traverse the pointer chain backwards and synthesize the suppressed stack trace.
- **Bounded Memory:** Bounded to `O(maxDepth)` with an origin-preserving sliding window to prevent leaks in long-lived or repeating streams.

---

## Ariadne vs. OpenTelemetry

While **OpenTelemetry** is built for *distributed tracing across services*, **Ariadne** is built for *ultra-low-overhead in-process causal reconstruction within the JVM*.

| Feature | **Ariadne** | **OpenTelemetry Java SDK** |
| :--- | :--- | :--- |
| **Primary Focus** | In-process asynchronous exception stack traces | Distributed cross-service request tracing |
| **Context Hop Cost** | **~5.8 ns / 40 B alloc** (immutable `Link`) | **~35–55 ns / 300+ B alloc** (Span, Scope, Timers) |
| **Normal Path Overhead** | Sub-microsecond; zero stack generation | Span lifecycle tracking and queue exporter buffers |
| **Failure Representation** | Direct `AsyncCausalityException` in `Throwable.addSuppressed()` | Error status / span event in external collector |
| **External Dependencies** | **Zero**; works in-memory with your existing logger | Requires Collector, Jaeger/Tempo/Zipkin, storage |
| **Trace Correlation** | **Built-in W3C & OTel Bridge:** attaches active OTel `[Trace: 00-...-01]` to JVM logs | Native distributed trace propagation |

> 💡 **Symbiosis:** They complement each other. With Ariadne's `ariadne-adapter-otel` (`-Dariadne.otel.bridge.enabled=true`), Ariadne captures the active OTel span at zero compile-time cost and attaches its W3C trace ID directly to JVM exception logs.

---

## Quick Start

### 1. Java Agent (Zero Code Changes)

Download `ariadne-agent-0.1.0-beta.1.jar` from [Releases](https://github.com/AfonsoPaiva/ARIADNE/releases) and attach it at startup:

```bash
java -javaagent:ariadne-agent-0.1.0-beta.1.jar -jar your-application.jar
```

Automatically instruments:
- `ExecutorService`, `ScheduledExecutorService`, `ForkJoinPool`
- `CompletableFuture` pipelines
- Java 21 Virtual Threads (`Thread.ofVirtual()`)
- Project Reactor & RxJava 3 schedulers
- SLF4J MDC context propagation

### 2. Dependency (Maven / Gradle)

**Maven:**
```xml
<dependency>
    <groupId>io.github.afonsopaiva</groupId>
    <artifactId>ariadne-core</artifactId>
    <version>0.1.0-beta.1</version>
</dependency>
```

**Gradle:**
```kotlin
implementation("io.github.afonsopaiva:ariadne-core:0.1.0-beta.1")
```

---

## Performance Summary (JMH on Java 21)

| Operation | Latency | Allocation | Mechanism |
| :--- | :---: | :---: | :--- |
| **Context Read (`current()`)** | ~2.7 ns | 0 B | Fast ThreadLocal / volatile carrier read |
| **Context Hop (`spawn`)** | ~5.8 ns | 40 B | 40-byte immutable node in TLAB |
| **Scoped Attach / Scope** | ~14.9 ns | 0 B | Reusable scope pool |
| **Call Site Resolution (`CLASS` mode)** | ~8.7 ns | 0 B | `ClassValue` cache (production default) |
| **CompletableFuture Pipeline** | ~1 to 2 µs | ~230 B/hop | Dominated by OS thread scheduling |
| **Project Reactor** | +0% to +2% | ~5x less alloc | Compared to `Hooks.onOperatorDebug()` |

---

## Ecosystem Modules

- **`ariadne-core`**: Core causality graph engine (pure Java 21, zero external dependencies).
- **`ariadne-agent`**: Zero-code ByteBuddy agent for transparent JVM bytecode instrumentation.
- **`ariadne-adapter-reactor`** & **`ariadne-adapter-rxjava`**: Reactive streams schedulers & assembly hooks.
- **`ariadne-adapter-mdc`**: SLF4J MDC context propagation across thread pools.
- **`ariadne-adapter-otel`**: Zero-dependency OpenTelemetry reflection bridge & W3C `traceparent` correlation.
- **`ariadne-adapter-kotlin`**: `ThreadContextElement` for Kotlin Coroutines (`launch`, `withContext`).

---

## Contributing

Contributions are welcome! Please check out our **[Contributing Guidelines](https://github.com/AfonsoPaiva/ARIADNE?tab=contributing-ov-file)** before submitting pull requests.

---

## Documentation & Wiki

For full architecture internals, JMX MBean monitoring (`io.ariadne:type=Ariadne`), Canary Probes, and configuration reference:

👉 **[Ariadne Technical Wiki & Reference Manual](https://afonsopaiva.github.io/ARIADNE/wiki.html)**

---

## License

Licensed under the **[Apache License 2.0](LICENSE)**.
