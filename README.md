<p align="center">
  <img src="docs/Images/logo-vertical-dark-background.svg" alt="Ariadne Logo" width="320">
</p>

<h3 align="center">Zero-overhead, production-safe asynchronous causality stack trace reconstruction for the JVM</h3>

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
  <a href="docs/wiki.html">
    <img src="https://img.shields.io/badge/📖_Explore-Technical_Wiki_%26_Docs-911010?style=for-the-badge" alt="Technical Wiki">
  </a>
  <a href="https://github.com/AfonsoPaiva/ARIADNE/releases">
    <img src="https://img.shields.io/badge/📦_Download-Release_JARs-2A2727?style=for-the-badge" alt="Releases">
  </a>
</p>

---

## What Ariadne Does

In modern JVM applications, asynchronous programming models—including thread pools (`ExecutorService`), `CompletableFuture`, Project Reactor, RxJava, and Java 21 Virtual Threads—sever traditional stack traces at thread boundaries.

When an exception is thrown on a worker thread, the stack trace stops at `ThreadPoolExecutor.runWorker()`. Developers are left with blind exceptions that show where the crash happened, but not **who scheduled it**, **which HTTP request triggered it**, or **what the causal chain was**.

### The Problem vs. The Solution

```
[DEFAULT JVM STACK TRACE]
java.lang.NullPointerException: Cannot invoke "Account.balance()"
    at com.app.billing.InvoiceWorker.process(InvoiceWorker.java:42)
    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(...)
    # BLIND SPOT: Originating caller context and HTTP parameters are permanently lost.

[WITH ARIADNE]
java.lang.NullPointerException: Cannot invoke "Account.balance()"
    at com.app.billing.InvoiceWorker.process(InvoiceWorker.java:42)
    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(...)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous causality trace
        at [Async Hop 2] com.app.billing.BillingService.chargeCustomer(BillingService.java:114)
        at [Async Hop 1] com.app.web.CheckoutController.submitOrder(CheckoutController.java:58)
        [Context: W3CTrace[traceId=4bf92f3577b34da6, userId=usr_42, mdc={tenantId=corp_acme}]]
```

### Why Ariadne is Production-Safe

Existing tools capture heavy `new Throwable()` snapshots on **every single task scheduling**, devouring CPU cycles and causing severe GC pressure (often 10×–100× throughput degradation).

Ariadne uses **lazy backward pointer traversal**:
1. **On the happy path (99.9% of executions):** Ariadne records an immutable 24-byte pointer (`Link`) in single-digit nanoseconds (~148 ns per async hop).
2. **On failure only (exceptions):** Ariadne traverses the pointer chain backwards, synthesizes the causal stack frames, and attaches them via `Throwable.addSuppressed()`.

---

## Quick Start

### 1. Java Agent (Zero Code Modifications)
Download `ariadne-agent-0.1.0-alpha.2.jar` from [Releases](https://github.com/AfonsoPaiva/ARIADNE/releases) and attach it to your application:

```bash
java -javaagent:ariadne-agent-0.1.0-alpha.2.jar -jar your-application.jar
```

The agent automatically instruments:
- `java.util.concurrent.Executor` & `ExecutorService`
- `java.util.concurrent.CompletableFuture`
- `java.util.concurrent.ForkJoinPool`
- Java 21 Virtual Threads (`Thread.ofVirtual()`)
- Project Reactor & RxJava 3 pipelines
- SLF4J MDC context propagation

### 2. Programmatic Integration (Maven / Gradle)

**Maven:**
```xml
<dependency>
    <groupId>io.ariadne</groupId>
    <artifactId>ariadne-core</artifactId>
    <version>0.1.0-alpha.2</version>
</dependency>
<!-- Optional SLF4J MDC adapter -->
<dependency>
    <groupId>io.ariadne</groupId>
    <artifactId>ariadne-adapter-mdc</artifactId>
    <version>0.1.0-alpha.2</version>
</dependency>
```

**Gradle:**
```kotlin
dependencies {
    implementation("io.ariadne:ariadne-core:0.1.0-alpha.2")
    implementation("io.ariadne:ariadne-adapter-mdc:0.1.0-alpha.2")
}
```

---

## Performance Summary (JMH 1.37 / Java 21)

| Operation | Observed Latency | Impact |
| :--- | :---: | :--- |
| **Context Read** | **1.6 ns** | Near CPU L1 cache speed |
| **Direct Link Creation** | **3.9 ns** | Immutable 24-byte record allocation |
| **Context Hop (`spawn`)** | **4.5 ns** | Read parent + allocate link + thread update |
| **Overhead per Async Hop** | **~148 ns** | Imperceptible vs. OS scheduler jitter |
| **Reactive Stream (100 elements)** | **+0.68 µs** | Less than 6% overhead across 100 items |

For comprehensive JMH graphs and methodology, see the [Technical Wiki](docs/wiki.html#benchmarks).

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

## Contributing

We welcome contributions to Ariadne! Whether you're reporting a bug, improving documentation, or submitting performance optimizations, follow these guidelines:

### Development Setup
- **Java:** JDK 21 LTS (OpenJDK, Eclipse Temurin, or GraalVM).
- **Build Tool:** Apache Maven 3.9+.

```bash
# 1. Clone your fork
git clone https://github.com/<your-username>/ARIADNE.git
cd ARIADNE

# 2. Compile and run all tests
mvn clean test

# 3. Verify bytecode compilation across all modules
mvn test-compile
```

### Core Design Rules for Contributions
1. **Zero External Production Dependencies in Core:** The `ariadne-core` module must remain completely free of external dependencies, relying only on the standard Java 21 runtime.
2. **Lock-Free & Low Allocation:** Hot execution paths must use lock-free atomic primitives, immutable 24-byte link records, and avoid thread contention.
3. **No ThreadLocal Leaks:** All context attachments must cleanly restore previous contexts upon task completion (e.g. via `try-with-resources`).
4. **Performance Verification:** Any changes touching scheduling, context propagation, or bytecode instrumentation must be benchmarked with JMH before submission:
   ```bash
   mvn package -DskipTests -pl ariadne-benchmarks
   java -jar ariadne-benchmarks/target/benchmarks.jar -f 1 -wi 2 -i 5
   ```

### Contribution Workflow
1. **Fork** the repository and create your feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. **Commit** your changes following standard commit conventions (e.g., `feat:`, `fix:`, `perf:`, `docs:`):
   ```bash
   git commit -m "perf: optimize context hop allocation in Link.java"
   ```
3. **Push** to your fork and submit a **Pull Request** to the `main` branch.
4. Ensure all CI checks and tests pass.

---

## Technical Documentation & Wiki

For complete architectural deep-dives, JMX MBean monitoring (`io.ariadne:type=AriadneManager`), bytecode transformation internals, Canary Probes, and distributed tracing bridges (W3C / OpenTelemetry / Kotlin Coroutines), visit the:

👉 **[Ariadne Technical Wiki & Reference Manual](docs/wiki.html)**

---

## License

Ariadne is open-source software licensed under the **[Apache License 2.0](LICENSE)**.
