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
  <a href="https://afonsopaiva.github.io/ARIADNE/wiki.html">
    <img src="https://img.shields.io/badge/📖_Explore-Technical_Wiki_%26_Docs-911010?style=for-the-badge" alt="Technical Wiki">
  </a>
  <a href="https://github.com/AfonsoPaiva/ARIADNE/releases">
    <img src="https://img.shields.io/badge/📦_Download-Release_JARs-2A2727?style=for-the-badge" alt="Releases">
  </a>
</p>

---

## What Ariadne Does

In modern JVM applications, asynchronous execution boundaries—such as thread pools (`ExecutorService`), `CompletableFuture`, Project Reactor, RxJava, and Java 21 Virtual Threads—sever traditional call stacks at thread boundaries.

When an unhandled exception is thrown on a worker thread, the JVM stack trace stops at `ThreadPoolExecutor.runWorker()`. Developers are left with truncated exceptions that show where the code crashed, but not **who scheduled it**, **which controller triggered it**, or **what the asynchronous causal chain was**.

### The Problem vs. The Solution

```
[DEFAULT JVM STACK TRACE]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1781)
    at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
    # BLIND SPOT: Originating caller method and HTTP request parameters are permanently lost.

[WITH ARIADNE — CLASS MODE (Default, Zero Overhead)]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {orderId=ORD-2026-999}]
        at io.ariadne.demo.PaymentService.lambda(PaymentService.java)
        at io.ariadne.agent.Executor.execute(Unknown Source)

[WITH ARIADNE — FULL MODE (-Dariadne.callsite.mode=full)]
java.lang.IllegalStateException: Payment gateway connection timeout [orderId=ORD-2026-999]
    at io.ariadne.demo.PaymentService.lambda$processPayment$0(PaymentService.java:18)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {orderId=ORD-2026-999}]
        at io.ariadne.demo.PaymentService.processPayment(PaymentService.java:16)
        at org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor$1.execute(ThreadPoolTaskExecutor.java:295)
```

### Why Ariadne is Designed for Production Safety

Traditional diagnostic tools capture full JVM stack traces (`new Throwable()`) on **every single task dispatch or operator creation**, devouring CPU cycles and causing severe GC pressure (often 10×–100× throughput degradation).

Ariadne uses **lazy backward pointer traversal**:
1. **On the happy path (99.9%+ of executions):** When a task is queued, Ariadne allocates an immutable 40-byte record (`Link`) in thread-local TLAB memory. In default `CLASS` mode, call sites are resolved via a `ClassValue<Integer>` cache avoiding `StackWalker` overhead on hot dispatch paths (~5.7 ns, 0 B allocation).
2. **End-to-End Hop Efficiency:** Because OS thread scheduling and handoff latency dominate async dispatch (~28 µs), nanosecond-level claims on multi-threaded dispatches are unrealistic. Instead, Ariadne delivers empirically defensible results:
   - **Project Reactor:** Sem diferença mensurável face ao baseline (+0 a 2%, dentro da margem de erro estatístico), com **~5x menos alocação** que o `Hooks.onOperatorDebug()` nativo do Reactor.
   - **Agente com CompletableFuture:** Overhead medido de **~1 a 2 µs por pipeline de 3 hops**, com uma pegada de alocação de **~230 B por hop** (incluindo o nó `Link` de 40 B, wrappers de execução e nós de continuação).
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

### Isolated Mechanical Operations (Core Micro-primitives)

| Operation | Isolated Latency | Memory Impact | Technical Mechanism |
| :--- | :---: | :---: | :--- |
| **Context Read (`current()`)** | **~2 ns** | **0 B** | Single volatile / ThreadLocal carrier read |
| **Direct Link Allocation** | **&lt; 4 ns** | **40 B** | HotSpot 64-bit object layout with Compressed OOPs |
| **Context Hop (`spawn`)** | **&lt; 6 ns** | **40 B** | Parent lookup + TLAB Link allocation + depth check |
| **Scoped Attach / Restore** | **&lt; 15 ns** | **0 B** | AutoCloseable scope restoring previous thread link |
| **Call Site Resolution (`CLASS` mode)** | **~5.7 ns** | **0.0 B** | `ClassValue<Integer>` user class resolution (zero GC churn) |
| **Call Site Resolution (`SAMPLED:100` mode)** | **~39.1 ns** | **~9.7 B** | 1 in 100 StackWalker sample, 99% ClassValue lookup |
| **Call Site Resolution (`FULL` mode)** | **~1.42 µs** | **~960 B** | Exact caller method and source line via `StackWalker` |

### Call Site Modes Configuration

Call site capture can be tailored to the environment via `-Dariadne.callsite.mode=<mode>` or env var `ARIADNE_CALLSITE_MODE`:

| Mode | Property / Setting | Trade-off & Behavior |
| :--- | :--- | :--- |
| **`class`** *(Default)* | `-Dariadne.callsite.mode=class` | Captures dispatching class (`PaymentService.lambda(PaymentService.java)`) at **~5.7 ns** and **0 B allocation**. Recommended for production. |
| **`sampled:N`** | `-Dariadne.callsite.mode=sampled:100` | Walks stack on 1 of every N dispatches (**~39 ns**, **~9.7 B/op** for N=100), serving the remaining 99% from `ClassValue`. |
| **`full`** | `-Dariadne.callsite.mode=full` | Traverses full call stack on every dispatch (**~1.42 µs**, **~960 B/op**) for exact method and line number (`PaymentService.processPayment(PaymentService.java:16)`). Recommended for staging and debugging. |

*Can also be inspected or modified at runtime via JMX MBean `io.ariadne:type=Ariadne` (`CallSiteMode` and `CallSiteSampleRate`).*

### End-to-End Asynchronous Pipelines (Multi-threaded & Agent)

Em execuções multithreaded reais (pools de threads, `CompletableFuture`, reatores), o tempo de handoff entre threads pelo sistema operacional (~28 µs) domina a latência absoluta, tornando variações de centenas de nanossegundos indistinguíveis do ruído do escalonador. Os dados de benchmark suportam com rigor as seguintes métricas:

- **Project Reactor:** Sem diferença mensurável face ao baseline (+0 a 2%, dentro da margem de erro estatístico), com **~5x menos alocação** que o `onOperatorDebug` nativo.
- **Agente com CompletableFuture:** **~1 a 2 µs por pipeline de 3 hops**, com **~230 B por hop** (compreendendo o nó `Link` de 40 B, wrappers `AriadneRunnable`/`AriadneCallable` e objetos de tarefa do JDK).

> 📊 **Metodologia Reproduzível:** Os microbenchmarks isolados são executados via `CoreOperationsBenchmark` eliminando o ruído de escalonamento do SO. Os benchmarks ponta a ponta multi-threaded são automatizados no [JMH Benchmark Workflow](.github/workflows/benchmarks.yml) (OpenJDK 21 HotSpot, `-prof gc`, 5 warmups, 5 iterações, 3 forks).

---

## Arquitetura e Limitações do Estado Estático Global

Para operar de forma transparente via Java Agent e adapters sem exigir modificações manuais de código, o Ariadne utiliza componentes globais bem delimitados. É fundamental compreender o design e as suas limitações operacionais:

1. **`SiteRegistry` (Cache Global de Call Sites):**
   - **Mecanismo:** Mantém mapas estáticos concorrentes (`ConcurrentHashMap`) indexando metadados de call sites para IDs inteiros compactos (`BY_ID`, `BY_METADATA`, `BY_DESCRIPTION`).
   - **Limitação / Trade-off:** O consumo de memória cresce proporcionalmente ao número de pontos de chamada distintos. Em aplicações padrão (Spring Boot, Reactor), o conjunto de pontos de injeção em bytecode é finito e pequeno (dezenas a centenas de registros). Contudo, em ambientes com geração dinâmica contínua e irrestrita de scripts/classes, o registro reteria referências indefinidamente. Para mitigar isso, o Ariadne disponibiliza `SiteRegistry.clear()` e `SiteRegistry.size()` para gestão e limpeza controlada.
2. **`AriadneContext` e ThreadLocals:**
   - **Mecanismo:** O contexto ativo por thread é gerenciado por `ThreadLocalContextCarrier`.
   - **Limitação / Trade-off:** Em pools de threads onde threads de trabalho são reutilizadas indefinidamente, um contexto que não fosse desanexado causaria "context leak" entre tarefas. O Ariadne previne isso estruturalmente através de blocos `try-finally` em todos os wrappers (`AriadneRunnable`, `AriadneCallable`) e no advice do agente (`@Advice.OnMethodExit`).
3. **Isolamento de ClassLoader no Java Agent:**
   - **Mecanismo:** O agente utiliza injeção no Bootstrap ClassLoader (`BootstrapInjector`) para que classes essenciais do núcleo (`io.ariadne.core`) estejam visíveis para classes do sistema (`java.base`, `java.util.concurrent`).
   - **Limitação:** Em servidores corporativos legados com múltiplos ClassLoaders hierárquicos (EAR/WAR multi-tenant), as classes de causalidade operam no escopo da JVM inteira.

---

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
2. **Commit** your changes following standard commit conventions (e.g., `feat:`, `fix:`, `perf:`, `docs:`):
   ```bash
   git commit -m "perf: optimize context hop allocation in Link.java"
   ```
3. **Push** to your fork and submit a **Pull Request** to the `main` branch.
4. Ensure all CI checks and tests pass.

---

## Technical Documentation & Wiki

For complete architectural deep-dives, JMX MBean monitoring (`io.ariadne:type=AriadneManager`), bytecode transformation internals, Canary Probes, and configuration reference, visit the:

👉 **[Ariadne Technical Wiki & Reference Manual](https://afonsopaiva.github.io/ARIADNE/wiki.html)**

---

## License

Ariadne is open-source software licensed under the **[Apache License 2.0](LICENSE)**.
