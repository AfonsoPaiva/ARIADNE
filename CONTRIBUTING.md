# Contributing to Ariadne

Thank you for your interest in contributing to **Ariadne**! We welcome bug fixes, performance improvements, documentation enhancements, and framework adapters.

---

## 1. Prerequisites & Environment Setup

- **Java Development Kit (JDK):** OpenJDK 21 LTS or newer (Eclipse Temurin 21 recommended).
- **Build Tool:** Apache Maven 3.9.0 or newer.
- **Git:** Version 2.30+.
- **Optional Tools:** Python 3.10+ (for benchmark table scripts), `wrk` (for HTTP load benchmarking).

---

## 2. Building the Codebase

Clone the repository and compile all modules:

```bash
git clone https://github.com/AfonsoPaiva/ARIADNE.git
cd ARIADNE

# Compile and package all modules
mvn clean package -DskipTests
```

---

## 3. Running Tests & Quality Checks

### Fast Unit & Adapter Tests
```bash
mvn test
```

### Full CI Verification (Coverage + SpotBugs)
```bash
mvn clean verify -P ci
```

### End-to-End Integration Tests (Forked JVM Agent)
```bash
mvn test -pl ariadne-integration-tests
```

### Spring Boot Production Load Benchmark
```bash
mvn test -Dtest=SpringBootThroughputP99Benchmark -f examples/spring-boot-demo/pom.xml
```

### Running JMH Microbenchmarks
```bash
java -jar ariadne-benchmarks/target/benchmarks.jar -rf json -rff benchmark-results.json -prof gc -wi 5 -i 5 -f 3
```

---

## 4. Architectural Rules & Design Principles

When contributing code to Ariadne, adhere strictly to the following architectural invariants:

1. **Zero Allocation on Critical Fast Paths:**
   - Any operation on `AriadneContext.current()`, `AriadneContext.attach()`, and `SiteRegistry.getOrRegister()` must allocate **0 bytes** (`0.0 B/op` under JMH `-prof gc`).
   - Use pooled scopes (`ReusableScope`) or direct primitive fields. Avoid lambda capture of local variables in tight loops.
2. **Total Fail-Safe Execution:**
   - The Java agent must never throw unhandled exceptions into user business logic.
   - Any exception during bytecode interception or causality stitching must be caught and logged (or swallowed safely).
3. **No ClassLoader Leaks:**
   - Never store direct references to `Class<?>` or `ClassLoader` in global static maps. `CallSiteMetadata` may only store primitives and `String` identifiers.
4. **Data Privacy First:**
   - Sensitive MDC context data must not be written to exception messages by default. Always respect the allowlist.

---

## 5. Pull Request Guidelines

1. **Create a Feature Branch:**
   ```bash
   git checkout -b feature/my-enhancement
   ```
2. **Commit Conventions:**
   Use clear, imperative commit messages (e.g., `feat(agent): add support for ScheduledExecutorService`, `fix(core): eliminate 40 B allocation in Scope`).
3. **Write Tests:**
   Every new feature or bug fix must be accompanied by corresponding JUnit 5 unit or integration tests.
4. **Run Quality Checks:**
   Verify `mvn clean verify -P ci` passes before opening your pull request.
5. **Update Documentation:**
   Update `CHANGELOG.md`, `README.md`, or `docs/wiki.html` if your change affects public APIs or configuration properties.
