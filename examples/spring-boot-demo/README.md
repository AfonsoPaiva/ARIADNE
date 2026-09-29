# Ariadne Spring Boot Demo (@Async & Project Reactor)

This demo demonstrates how Ariadne eliminates asynchronous blind spots in a Spring application using:
1. **Spring `@Async`** backed by `ThreadPoolTaskExecutor` calling `CompletableFuture.supplyAsync()`
2. **Project Reactor / WebFlux** pipelines dispatching across `boundedElastic()` and `parallel()` schedulers
3. **SLF4J MDC context propagation** preserving correlation IDs (`traceId`, `userId`)

---

## How to Build

From the repository root:
```bash
# 1. Package Ariadne parent and all modules
mvn package -DskipTests -o

# 2. Package the demo application
mvn -f examples/spring-boot-demo/pom.xml package -DskipTests -o
```

---

## How to Run & Compare (Before vs After)

### Scenario A: Without Ariadne Agent (Default JVM Behavior)

```bash
java -jar examples/spring-boot-demo/target/spring-boot-demo-0.1.0-alpha.2.jar
```

**Observation:**
- When `@Async` fails, the JVM stack trace starts at `ThreadPoolExecutor$Worker.run()`.
- The caller `OrderService.placeOrderAsync(...)` and the HTTP controller are **permanently lost**.

---

### Scenario B: With Ariadne Agent (Attached via `-javaagent`)

```bash
java -javaagent:ariadne-agent/target/ariadne-agent-0.1.0-alpha.2.jar \
     -jar examples/spring-boot-demo/target/spring-boot-demo-0.1.0-alpha.2.jar
```

**Observation:**
- The caught exception contains an attached suppressed `AsyncCausalityException`.
- The synthetic stack trace accurately reconstructs the full causal chain:
  ```
  Suppressed: io.ariadne.core.AsyncCausalityException: Asynchronous execution path (2 hops) [Context: {traceId=req-89a1f4b2, userId=afonso-paiva}]
      at io.ariadne.demo.PaymentService.processPayment(PaymentService.java:16)
      at io.ariadne.demo.OrderService.placeOrderAsync(OrderService.java:27)
  ```
- MDC correlation keys are seamlessly propagated without manual code wrappers.
