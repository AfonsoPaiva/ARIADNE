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
