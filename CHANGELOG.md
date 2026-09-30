# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- Java 21 Scoped Values (`java.lang.ScopedValue`) carrier preview (JEP 446 / JEP 464).
- OpenTelemetry trace context link bridge (`Traceparent` causal correlation).
- Micrometer integration metrics exporter.

---

## [0.1.0-alpha.3] - 2026-09-30

### Added
- **SpotBugs `@Advice` False-Positive Exclusions**: Added targeted exclusions in `spotbugs-exclude.xml` for ByteBuddy `@Advice` inlined fields, eliminating false-positive `MS_EXPOSE_REP` warnings from static public fields required by the bytecode instrumentation contract.
- **Bootstrap Carrier Restoration Logging**: `AriadneMdcAdapter` now emits a `DEBUG`-level log when bootstrap carrier restoration fails (e.g. agent not attached), improving diagnosability in partial-deployment scenarios.
- **Technical Wiki & Reference Manual**: Comprehensive `docs/wiki.html` covering architecture, JMX MBean monitoring, bytecode transformation internals, Canary Probes, and full configuration reference.
- **Dynamic Favicon and Navigation Styling**: Improved `docs/index.html` with dynamic favicon and navigation button styling.

### Changed
- **Group ID updated to `io.github.afonsopaiva`**: Maven coordinates across all modules updated to the verified Maven Central namespace.
- **Executor and Thread Advice hardened**: `ExecutorAdvice` and `ThreadAdvice` extended with improved causality tracking for edge cases including `CallerRunsPolicy` nested submissions and direct `Thread.start()` instrumentation.
- **Call Site Resolution Modes**: Configurable `class` / `sampled:N` / `full` modes introduced in `AriadneConfig`, documented with JMH benchmark data per mode.
- **Benchmark parameters elevated**: Default JMH parameters raised to 3 forks, 5 warmup + 5 measurement iterations with `-prof gc` for statistically rigorous results.
- **README rewritten**: Performance metrics, architecture documentation, and trade-off disclosures updated for accuracy and consistency.
- **Spring version bump**: `spring-expression` updated to `6.2.19` in the Spring Boot demo example.

### Fixed
- **Bootstrap carrier synchronization simplified**: Removed unnecessary complexity in `AriadneMdcAdapter` bootstrap carrier handoff; improved test coverage for MDC propagation edge cases.
- **Edge case tests added**: MDC, Reactor, and RxJava adapter tests extended to cover null context, empty context map, and adapter install/uninstall cycles.

---

## [0.1.0-alpha.2] - 2026-09-30

### Added
- **SLF4J MDC Context Propagation**: New `ariadne-adapter-mdc` module for automatic cross-thread transfer of logging diagnostic contexts with configurable allowlist and default data leak prevention.
- **Production Load Benchmark Suite**: High-concurrency Spring Boot load benchmark (`SpringBootThroughputP99Benchmark`) with 16 concurrent workers, 25,000 requests, and automated wrk/JMeter runner (`scripts/benchmark_spring_wrk.sh`).
- **Zero-Allocation Scoped Pooling**: Recycled `ReusableScope` pool in `ThreadLocalContextCarrier` eliminating 40 B allocation down to `0.0 B/op`.
- **Zero-Allocation Call Site Lookup**: Direct `BY_DESCRIPTION` query in `SiteRegistry.getOrRegister` eliminating 64 B allocation down to `0.0 B/op` (1.28 ns).
- **Forked JVM Integration Test**: `ForkedJvmAgentIntegrationTest` launching an isolated external JVM with `-javaagent` argument to verify real production bytecode transformation.
- **Dynamic Kill Switch & Exclusion List**: Runtime toggle `-Dariadne.enabled=false` and class prefix exclusion list (`AriadneConfig.isClassExcluded()`).
- **JMX MBean Observability**: `io.ariadne:type=Ariadne` (`AriadneMXBean`) allowing runtime monitoring of hops, active threads, and on-the-fly tuning of call site capture modes.
- **Automated Benchmark Publishing**: `scripts/generate_benchmark_tables.py` and `scripts/compare_benchmarks.py` generating Markdown and HTML summaries directly from JMH JSON metrics.

### Changed
- **Maven Central Coordinates**: Updated group ID to verified namespace `io.github.afonsopaiva` across all modules.
- **CI Pipeline Hardening**: Matrix testing across OpenJDK 21 LTS and OpenJDK 23 with JaCoCo code coverage thresholds and SpotBugs static analysis.
- **Statistical Rigor in Benchmarks**: Default benchmark parameters elevated to 3+ JVM forks and 5 warmup + 5 measurement iterations with compiler blackholes.
- **Memory Footprint Bounds**: Bounded `SiteRegistry` capacity (`MAX_SITES = 65,536`) to guard against heap exhaustion from dynamic class generation.

### Fixed
- **Recursive Submit Guard**: Replaced binary `ThreadLocal<Boolean>` with depth counter `ThreadLocal<Integer>` to support nested submissions under `CallerRunsPolicy`.
- **Bootstrap Search Path Documentation**: Corrected documentation in `BootstrapInjector` regarding JVM agent classloader mechanics and dynamic bootstrap injection.
- **Security Update (AssertJ XXE Alert #5)**: Upgraded `assertj-core` to `3.27.7` in `examples/spring-boot-demo/pom.xml`, eliminating the XML External Entity (XXE) vulnerability (CWE-611).
- **CI Build & Static Analysis Stability**: Configured default `argLine` property in `pom.xml` preventing Surefire `@file` expansion errors when running without JaCoCo, and added representation exposure exclusions in `spotbugs-exclude.xml` for zero-allocation singletons and diagnostic records (`MS_EXPOSE_REP`, `EI_EXPOSE_REP`, `EI_EXPOSE_REP2`).

---

## [0.1.0-alpha.1] - 2026-09-28

### Added
- **Core Causality Engine**: Immutable `io.ariadne.core.Link` singly-linked data structure with strict 40-byte footprint under 64-bit Compressed OOPs.
- **ByteBuddy Java Agent**: Zero-code instrumentation for `ThreadPoolExecutor`, `ForkJoinPool`, `CompletableFuture`, and `Thread.start()`.
- **Reactive Stream Adapters**: Schedulers hooks for Project Reactor 3 (`Schedulers.onScheduleHook`) and RxJava 3 (`RxJavaPlugins.setScheduleHandler`).
- **Lazy Backward Reconstructor**: On-demand stack trace synthesis traversing causal links only upon exception interception.
- **Microbenchmark Suite**: Initial JMH suite measuring nanosecond overhead for context propagation and allocation.
