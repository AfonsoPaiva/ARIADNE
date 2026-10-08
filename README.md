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
- **Normal Path (99.9%+):** Allocates a single immutable 40-byte pointer node (`Link`) in thread-local memory. No stack traces or Throwables are captured during task submission (1.55 ns allocation, 7.05 ns spawn).
- **Failure Path Only:** Only when an unhandled exception is thrown does Ariadne traverse the pointer chain backwards and synthesize the suppressed stack trace.
- **Bounded Memory:** Bounded to `O(maxDepth)` with an origin-preserving sliding window to prevent leaks in long-lived or repeating streams.

---

## Ariadne vs. OpenTelemetry

While **OpenTelemetry** is designed for *distributed tracing across network boundaries*, **Ariadne** is purpose-built for *ultra-low-overhead in-process causal reconstruction within the JVM*.

### Head-to-Head JMH Benchmark Results (Java 21 HotSpot, `-prof gc`)

| Operation / Dimension | **Baseline** | **Ariadne** | **OpenTelemetry Java SDK** | Comparison |
| :--- | :---: | :---: | :---: | :--- |
| **Trace Node Creation** (`Link` vs. `Span`) | — | **8.16 ns** / `40.0 B` | **115.62 ns** / `368.0 B` | **Ariadne is 14.2x faster**, **9.2x less memory** |
| **Direct Link Allocation** | — | **4.41 ns** / `40.0 B` | **115.62 ns** / `368.0 B` | **26x faster** (TLAB immutable node) |
| **Scope Activation** (`try-with-resources`) | — | **11.24 ns** / **0.0 B** | **4.63 ns** / **32.0 B** | **Ariadne allocates 0 B**; OTel allocates 32 B/op |
| **Context Read** (`current`) | — | **1.60 ns** / `0.0 B` | **0.90 ns** / `0.0 B` | ThreadLocal carrier read |
| **Java 21 Virtual Threads** | 3.25 µs / `399.9 B` | **3.02 µs** / `656.0 B` | **3.14 µs** / `560.0 B` | Both match Loom scheduler performance |
| **Platform Thread Pool Submit** | 5.03 µs / `112.2 B` | **5.83 µs** / `168.2 B` | **5.08 µs** / `136.2 B` | Sub-microsecond execution |
| **CompletableFuture Single-Hop** | 5.11 µs / `160.2 B` | **5.71 µs** / `213.4 B` | **5.08 µs** / `194.8 B` | Minimal propagation overhead (<60 B) |

Run this head-to-head benchmark on your machine:
```bash
java -jar ariadne-benchmarks/target/benchmarks.jar OpenTelemetryComparisonBenchmark -f 1 -wi 2 -i 3 -prof gc
```

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
| **Context Read (`current()`)** | 1.43 ns | 0.0 B | Fast ThreadLocal / volatile carrier read |
| **Direct Link Allocation** | 1.55 ns | 40.0 B | 40-byte immutable node in TLAB |
| **Context Hop (`spawn`)** | 7.05 ns | 40.0 B | Atomic Link allocation + depth bounds check |
| **Scoped Attach & Scope** | 10.28 ns | 0.0 B | Reusable scope pool (`try-with-resources`) |
| **Cached Site Registry Lookup** | 1.25 ns | 0.0 B | Lock-free site cache |
| **Call Site Resolution (`CLASS` mode)** | 5.29 ns | 0.0 B | `ClassValue` cache (production default) |
| **Call Site Resolution (`SAMPLED` mode)** | 21.11 ns | 10.1 B | 1:N StackWalker sampling |
| **CompletableFuture Single-Hop** | 18.84 µs | 216.0 B | Identical to baseline (18.99 µs, within error margin) |
| **CompletableFuture Multi-Hop** | 18.69 µs | 536.3 B | Baseline 18.41 µs (delta: +0.27 µs) |
| **Project Reactor (Mono Hop)** | 19.13 µs | 488.0 B | Baseline 18.85 µs (+1.5%); **~5.8x less alloc** than `onOperatorDebug` (2848 B) |
| **Project Reactor (Flux Stream Hop)** | 20.87 µs | 3864.1 B | Baseline 20.62 µs (+1.2%); **~1.8x less alloc** than `onOperatorDebug` (7064 B) |
| **Virtual Thread Execution** | 18.07 µs | 624.1 B | Baseline 17.55 µs (+2.9%) |

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
