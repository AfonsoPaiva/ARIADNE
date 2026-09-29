# Compatibility Matrix

This document defines the verified runtime environments, framework integrations, and supported version baselines for **Ariadne**.

---

## 1. Java Runtimes (JDK)

Ariadne requires **Java 21 LTS** as its minimum baseline to leverage modern JVM features (Virtual Threads, Pattern Matching, Sealed Types, `ClassValue` optimizations).

| Java Version | Release Type | Distribution Tested | Support Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Java 21 LTS** | LTS Baseline | Eclipse Temurin, OpenJDK, GraalVM | **Fully Supported & Verified** | Default production target; primary CI matrix runner. |
| **Java 22** | STS | Eclipse Temurin | **Compatible** | Verified with `-Dnet.bytebuddy.experimental=true`. |
| **Java 23** | STS | Eclipse Temurin | **Fully Supported & Verified** | Actively verified in CI matrix. |
| **Java 25 LTS** | Upcoming LTS | Early Access | **Planned (v0.2.0)** | Targeting Scoped Values (`ScopedValue`) production carrier. |

---

## 2. Reactive Frameworks

Ariadne tracks causality across reactive operators, schedulers, and publish/subscribe boundaries with zero heap-walk overhead.

| Framework | Supported Versions | Integration Adapter | Mechanism |
| :--- | :--- | :--- | :--- |
| **Project Reactor** | `3.4.x` – `3.6.x+` | `ariadne-adapter-reactor` | `Schedulers.onScheduleHook` & `Hooks.onOperatorError` |
| **RxJava 3** | `3.0.x` – `3.1.x+` | `ariadne-adapter-rxjava` | `RxJavaPlugins.setScheduleHandler` |
| **SmallRye Mutiny** | `2.x` | Roadmap (v0.3.0) | Planned reactive hook adapter |

---

## 3. Spring Framework & Spring Boot

Ariadne automatically preserves causality across Spring `@Async` boundaries, `ThreadPoolTaskExecutor`, and reactive WebFlux endpoints.

| Framework | Supported Versions | Verified Components | Support Status |
| :--- | :--- | :--- | :--- |
| **Spring Boot** | `3.0.x` – `3.4.x+` | `@Async`, `ThreadPoolTaskExecutor`, WebFlux, MVC Async | **Fully Supported & Verified** |
| **Spring Framework** | `6.0.x` – `6.2.x+` | `TaskExecutor`, `CompletableFuture`, `AnnotationConfigApplicationContext` | **Fully Supported & Verified** |

---

## 4. Logging & Diagnostics (MDC)

| Component | Supported Versions | Integration Module | Notes |
| :--- | :--- | :--- | :--- |
| **SLF4J** | `2.0.x+` | `ariadne-adapter-mdc` | Automatic cross-thread MDC context transfer |
| **Logback** | `1.4.x`, `1.5.x+` | Tested with `ariadne-adapter-mdc` | Automatic MDC context preservation |
| **Log4j 2** | `2.20.x+` | Compatible via SLF4J bridge | SLF4J MDC bridge supported |

---

## 5. Concurrency Primitives & Pools

Ariadne instruments standard JDK concurrency classes out of the box via its zero-code Java Agent:

- `java.util.concurrent.ThreadPoolExecutor` (`submit`, `execute`)
- `java.util.concurrent.ForkJoinPool` (`submit`, `execute`, `invoke`)
- `java.util.concurrent.CompletableFuture` (`supplyAsync`, `runAsync`, `thenApplyAsync`, `thenAcceptAsync`, `thenRunAsync`)
- `java.lang.Thread` (`start()`, `Thread.ofVirtual().start()`)

---

## 6. Bytecode Transformation & Shading

To prevent classloader collisions with application dependencies:
- **ByteBuddy 1.14.x** is shaded internally under the package `io.ariadne.shaded.bytebuddy.*`.
- Ariadne core classes are injected into the JVM **Bootstrap ClassLoader Search Path**, ensuring visibility to `java.base` regardless of the application classloader topology.
