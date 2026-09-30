<p align="center">
  <img src="docs/Images/logo-vertical-dark-background.svg" alt="Ariadne Logo" width="320">
</p>

<h3 align="center">Low-overhead, production-safe asynchronous causality stack trace reconstruction for the JVM</h3>

<p align="center">
  <a href="https://github.com/AfonsoPaiva/ARIADNE/releases/tag/v0.1.0-alpha.2"><img src="https://img.shields.io/badge/Release-v0.1.0--alpha.2-911010?style=for-the-badge" alt="Release"></a>
  <img src="https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Byte%20Buddy-1.14.12-2962FF?style=for-the-badge" alt="Byte Buddy">
  <img src="https://img.shields.io/badge/Project%20Reactor-3.6.4-6DB33F?style=for-the-badge&logo=reactivex&logoColor=white" alt="Reactor">
  <img src="https://img.shields.io/badge/RxJava-3.1.8-B7178C?style=for-the-badge&logo=reactivex&logoColor=white" alt="RxJava">
  <img src="https://img.shields.io/badge/SLF4J-MDC-2563EB?style=for-the-badge" alt="SLF4J">
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

In modern JVM applications, asynchronous execution boundaries such as thread pools (`ExecutorService`), `CompletableFuture`, Project Reactor, RxJava, and Java 21 Virtual Threads sever traditional call stacks at thread boundaries.

When an unhandled exception is thrown on a worker thread, the JVM stack trace stops at `ThreadPoolExecutor.runWorker()`. Developers are left with truncated exceptions that show **where** the code crashed, but not **who scheduled it**, **which controller triggered it**, or **what the asynchronous causal chain was**.

### The Problem vs. The Solution

```
[DEFAULT JVM STACK TRACE]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1781)
    at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
    # BLIND SPOT: Originating caller method and HTTP request parameters are permanently lost.

[WITH ARIADNE - CLASS MODE (Default, Zero Overhead)]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {orderId=ORD-2026-999}]
        at io.ariadne.demo.PaymentService.lambda(PaymentService.java)
        at io.ariadne.agent.Executor.execute(Unknown Source)

[WITH ARIADNE - FULL MODE (-Dariadne.callsite.mode=full)]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {orderId=ORD-2026-999}]
        at io.ariadne.demo.PaymentService.processPayment(PaymentService.java:16)
        at org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1.execute(ThreadPoolTaskExecutor.java:295)
```

### Why Ariadne Is Designed for Production Safety

Traditional diagnostic tools capture full JVM stack traces (`new Throwable()`) on **every single task dispatch or operator creation**, consuming CPU cycles and causing severe GC pressure that often results in 10x to 100x throughput degradation.

Ariadne uses **lazy backward pointer traversal** instead:

1. **On the happy path (99.9%+ of executions):** When a task is queued, Ariadne allocates an immutable 40-byte record (`Link`) in thread-local TLAB memory. In default `CLASS` mode, call sites are resolved via a `ClassValue<Integer>` cache, avoiding `StackWalker` overhead on hot dispatch paths (~5.7 ns, 0 B allocation).
2. **End-to-end hop efficiency:** Because OS thread scheduling and handoff latency dominate async dispatch (~28 µs), nanosecond-level claims on multi-threaded dispatches are unrealistic. Ariadne delivers empirically defensible results:
   - **Project Reactor:** No measurable difference versus baseline (+0 to 2%, within statistical error margin), with approximately 5x less allocation than native `Hooks.onOperatorDebug()`.
   - **Java Agent with CompletableFuture:** Measured overhead of ~1 to 2 µs per 3-hop pipeline, with an allocation footprint of ~230 B per hop (including the 40 B `Link` node, execution wrappers, and continuation nodes).
3. **Memory retention protection:** Chains are capped at a configurable depth (`ariadne.max.depth`, default: 32) using a sliding window strategy at spawn time, pruning the oldest hops to allow garbage collection while preserving recent causal context on long-lived, repeating, or recursive tasks.
4. **On failure only (exceptions):** Ariadne traverses the pointer chain backwards, synthesizes the causal stack frames, and attaches them directly via `Throwable.addSuppressed()`.

---

## How Ariadne Compares to Existing Tools

| Dimension | **Ariadne** | **ReactorDebugAgent (`reactor-tools`)** | **OpenTelemetry Java Agent** |
| :--- | :---: | :---: | :---: |
| **Primary Goal** | In-process asynchronous exception causality | Operator assembly debugging | Distributed cross-service tracing |
| **Supported Boundaries** | Executors, Loom, CompletableFuture, Reactor, RxJava, MDC | Project Reactor exclusively | Network, HTTP, JDBC, Executors |
| **Exception Enrichment** | Direct causal frames in `Throwable.addSuppressed()` | Reconstructed assembly in error message | Error span status in collector |
| **Infrastructure Overhead** | Zero external dependencies; local in-memory | Zero external dependencies; Reactor only | Requires OTel Collector, Jaeger/Zipkin |
| **Happy Path Overhead** | ~150 ns per async hop | Low (bytecode instrumentation at class load) | Variable (span creation & propagation) |

---

## Quick Start

### 1. Java Agent (Zero Code Modifications)

Download `ariadne-agent-0.1.0-alpha.2.jar` from [Releases](https://github.com/AfonsoPaiva/ARIADNE/releases) and attach it to your application:

```bash
java -javaagent:ariadne-agent-0.1.0-alpha.2.jar -jar your-application.jar
```

The agent automatically instruments:

- `java.util.concurrent.Executor`, `ExecutorService` & `ScheduledExecutorService` (`execute`, `submit`, `schedule`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`)
- `java.util.concurrent.CompletableFuture` (`supplyAsync`, `runAsync`, `then*Async`, `handleAsync`, `whenCompleteAsync`, `delayedExecutor`)
- `java.util.concurrent.ForkJoinPool` (via `ExecutorService.execute(Runnable)` / `submit`)
- Java 21 Virtual Threads (via `Thread.ofVirtual().start()`, `Thread.startVirtualThread()`, and `Executors.newVirtualThreadPerTaskExecutor()`)
- Project Reactor & RxJava 3 schedulers and error hooks
- SLF4J MDC context propagation (automatic snapshotting across boundaries)

### 2. Programmatic Integration (Maven / Gradle)

Ariadne is configured for publication to **Maven Central** under the verified namespace `io.github.afonsopaiva`:

**Maven:**
```xml
<dependency>
    <groupId>io.github.afonsopaiva</groupId>
    <artifactId>ariadne-core</artifactId>
    <version>0.1.0-alpha.2</version>
</dependency>
<!-- Optional SLF4J MDC adapter -->
<dependency>
    <groupId>io.github.afonsopaiva</groupId>
    <artifactId>ariadne-adapter-mdc</artifactId>
    <version>0.1.0-alpha.2</version>
</dependency>
```

**Gradle:**
```kotlin
dependencies {
    implementation("io.github.afonsopaiva:ariadne-core:0.1.0-alpha.2")
    implementation("io.github.afonsopaiva:ariadne-adapter-mdc:0.1.0-alpha.2")
}
```

Pre-built Java Agent JARs are also directly downloadable from [GitHub Releases](https://github.com/AfonsoPaiva/ARIADNE/releases).

---

## Performance Summary (JMH 1.37 / Java 21)

### Isolated Mechanical Operations (Core Micro-primitives)

| Operation | Isolated Latency | Memory Impact | Technical Mechanism |
| :--- | :---: | :---: | :--- |
| **Context Read (`current()`)** | ~2 ns | 0 B | Single volatile / ThreadLocal carrier read |
| **Direct Link Allocation** | < 4 ns | 40 B | HotSpot 64-bit object layout with Compressed OOPs |
| **Context Hop (`spawn`)** | < 6 ns | 40 B | Parent lookup + TLAB Link allocation + depth check |
| **Scoped Attach / Restore** | < 15 ns | 0 B | AutoCloseable scope restoring previous thread link |
| **Call Site Resolution (`CLASS` mode)** | ~5.7 ns | 0.0 B | `ClassValue<Integer>` user class resolution (zero GC churn) |
| **Call Site Resolution (`SAMPLED:100` mode)** | ~39.1 ns | ~9.7 B | 1 in 100 StackWalker sample, 99% ClassValue lookup |
| **Call Site Resolution (`FULL` mode)** | ~1.42 µs | ~960 B | Exact caller method and source line via `StackWalker` |

### Call Site Modes Configuration

Call site capture can be tailored to the environment via `-Dariadne.callsite.mode=<mode>` or the environment variable `ARIADNE_CALLSITE_MODE`:

| Mode | Property / Setting | Trade-off & Behavior |
| :--- | :--- | :--- |
| **`class`** *(Default)* | `-Dariadne.callsite.mode=class` | Captures the dispatching class (`PaymentService.lambda(PaymentService.java)`) at ~5.7 ns and 0 B allocation. Recommended for production. |
| **`sampled:N`** | `-Dariadne.callsite.mode=sampled:100` | Walks the stack on 1 of every N dispatches (~39 ns, ~9.7 B/op for N=100), serving the remaining 99% from `ClassValue`. |
| **`full`** | `-Dariadne.callsite.mode=full` | Traverses the full call stack on every dispatch (~1.42 µs, ~960 B/op) for exact method and line number (`PaymentService.processPayment(PaymentService.java:16)`). Recommended for staging and debugging. |

The call site mode can also be inspected or modified at runtime via JMX MBean `io.ariadne:type=Ariadne` (attributes `CallSiteMode` and `CallSiteSampleRate`).

### End-to-End Asynchronous Pipelines (Multi-threaded & Agent)

In real-world multi-threaded execution (thread pools, `CompletableFuture`, reactive streams), OS thread scheduling and handoff latency (~28 µs) dominate absolute latency, making sub-microsecond variations indistinguishable from scheduler jitter. The benchmark data supports the following metrics:

- **Project Reactor:** No measurable difference versus baseline (+0 to 2%, within statistical error margin), with approximately 5x less allocation than native `onOperatorDebug`.
- **Java Agent with CompletableFuture:** ~1 to 2 µs per 3-hop pipeline, with ~230 B per hop (comprising the 40 B `Link` node, `AriadneRunnable`/`AriadneCallable` wrappers, and JDK task objects).

> 📊 **Reproducible Methodology:** Isolated microbenchmarks run via `CoreOperationsBenchmark` to eliminate OS scheduling noise. End-to-end multi-threaded benchmarks are automated in the [JMH Benchmark Workflow](.github/workflows/benchmarks.yml) (OpenJDK 21 HotSpot, `-prof gc`, 5 warmups, 5 iterations, 3 forks).

---

## Architecture and Limitations of Global Static State

To operate transparently via the Java Agent and adapters without requiring manual code modifications, Ariadne relies on well-scoped global static components. Understanding the design and its operational trade-offs is important for production deployments.

### 1. `SiteRegistry` (Global Call Site Cache)

- **Mechanism:** Maintains static concurrent maps (`ConcurrentHashMap`) indexing call site metadata to compact integer IDs (`BY_ID`, `BY_METADATA`, `BY_DESCRIPTION`).
- **Trade-off:** Memory consumption grows proportionally with the number of unique call sites. In standard enterprise applications (Spring Boot, Reactor), bytecode injection points are finite and small (dozens to hundreds of records). In environments with continuous, unbounded dynamic class or script generation, the registry retains metadata indefinitely. Ariadne provides `SiteRegistry.clear()` and `SiteRegistry.size()` for controlled management and testing.

### 2. `AriadneContext` and ThreadLocals

- **Mechanism:** Active thread context is managed by `ThreadLocalContextCarrier`.
- **Trade-off:** In thread pools where worker threads are reused indefinitely, failing to detach a context causes cross-task context leakage. Ariadne prevents this structurally via mandatory `try-finally` blocks across all execution wrappers (`AriadneRunnable`, `AriadneCallable`) and Java Agent advice (`@Advice.OnMethodExit`).

### 3. ClassLoader Isolation in Java Agent

- **Mechanism:** The agent injects essential core classes (`io.ariadne.core`) into the Bootstrap ClassLoader search path (`BootstrapInjector`) so they are visible to JDK system classes (`java.base`, `java.util.concurrent`).
- **Limitation:** In legacy enterprise application servers with complex hierarchical ClassLoaders (multi-tenant EAR/WAR containers), causality engine classes operate at the whole-JVM scope.

---

## Repository Structure

```
ARIADNE/
├── ariadne-core/               # Pure Java 21 causality graph engine (0 external dependencies)
├── ariadne-agent/              # ByteBuddy JVM Java Agent for transparent bytecode instrumentation
├── ariadne-adapter-reactor/    # Project Reactor scheduler hooks & canary probe health-checks
├── ariadne-adapter-rxjava/     # RxJava 3 assembly & schedule hooks with fail-safe degradation
├── ariadne-adapter-mdc/        # SLF4J MDC context propagation across thread boundaries
├── ariadne-benchmarks/         # Standalone JMH microbenchmark suite
├── ariadne-integration-tests/  # End-to-end integration tests & multi-hop verification
└── docs/                       # Interactive documentation portal & technical wiki
```

---

## Roadmap (Milestone v0.2.0)

The following extensions are planned and currently under active design:

- [ ] **W3C TraceContext Integration:** Propagation of `traceparent` headers across thread boundaries.
- [ ] **OpenTelemetry Reflection Bridge:** Synchronizing active OTel spans with in-process causality.
- [ ] **Java 21 Scoped Values:** Lexical scope carrier using JDK 21 `java.lang.ScopedValue`.
- [ ] **Kotlin Coroutines Bridge:** `ThreadContextElement` adapter for Kotlin suspend/resume flows.

---

## Contributing

Contributions to Ariadne are welcome. Whether you are reporting a bug, improving documentation, or submitting a performance optimization, please follow the guidelines below.

### Development Setup

- **Java:** JDK 21 LTS (OpenJDK, Eclipse Temurin, or GraalVM)
- **Build Tool:** Apache Maven 3.9+

```bash
# 1. Clone your fork
git clone https://github.com/<your-username>/ARIADNE.git
cd ARIADNE

# 2. Compile and run all tests
mvn clean test

# 3. Verify bytecode compilation across all modules
mvn test-compile
```

### Core Design Rules

1. **Zero External Production Dependencies in Core:** The `ariadne-core` module must remain completely free of external dependencies, relying only on the standard Java 21 runtime.
2. **Lock-Free & Low Allocation:** Hot execution paths must use lock-free atomic primitives, immutable 40-byte link records, and avoid thread contention.
3. **No ThreadLocal Leaks:** All context attachments must cleanly restore previous contexts upon task completion (e.g. via `try-with-resources`).
4. **Memory Retention Safety:** Causal depth must be capped to prevent unbounded retention in long-lived or recursive task chains.
5. **Performance Verification:** Any changes touching scheduling, context propagation, or bytecode instrumentation must be benchmarked with JMH before submission:
   ```bash
   mvn package -DskipTests -pl ariadne-benchmarks
   java -jar ariadne-benchmarks/target/benchmarks.jar -f 1 -wi 2 -i 5
   ```

### Contribution Workflow

1. **Fork** the repository and create your feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. **Commit** your changes following standard commit conventions (`feat:`, `fix:`, `perf:`, `docs:`):
   ```bash
   git commit -m "perf: optimize context hop allocation in Link.java"
   ```
3. **Push** to your fork and open a **Pull Request** against the `main` branch.
4. Ensure all CI checks and tests pass before requesting a review.

---

## Project Documentation

- 📋 **[Compatibility Matrix](COMPATIBILITY.md)** - Verified JDKs (Java 21, 23), Project Reactor, RxJava, and Spring Boot versions.
- 📦 **[Changelog](CHANGELOG.md)** - Full release history following Keep a Changelog and Semantic Versioning.
- 🤝 **[Contributing Guidelines](CONTRIBUTING.md)** - Development environment setup, quality checks, and architectural invariants.
- 🛡️ **[Security Policy](SECURITY.md)** - Vulnerability reporting instructions, fail-safe rules, and data leak prevention.

---

## Technical Documentation & Wiki

For complete architectural deep-dives, JMX MBean monitoring (`io.ariadne:type=Ariadne`), bytecode transformation internals, Canary Probes, and the full configuration reference, visit the:

👉 **[Ariadne Technical Wiki & Reference Manual](https://afonsopaiva.github.io/ARIADNE/wiki.html)**

---

## License

Ariadne is open-source software licensed under the **[Apache License 2.0](LICENSE)**.
