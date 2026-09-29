package io.ariadne.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Memory leak and stability verification test suite:
 * 1. Long-lived thread pools with worker thread reuse (verifying ThreadLocal cleanup).
 * 2. Cancelled tasks (Future.cancel) with no lingering context references.
 * 3. ThreadLocal context cleanup under nested task dispatches.
 * 4. Soak test verifying depth capping and bounded heap memory over 100,000 hops.
 */
class MemoryLeakAndStabilityTest {

    @BeforeEach
    @AfterEach
    void resetState() {
        AriadneConfig.resetDefaults();
        AriadneContext.clear();
    }

    @Test
    @DisplayName("Thread Pool Reuse: Long-lived worker threads must clean up ThreadLocal after task completion")
    void shouldCleanUpThreadLocalOnWorkerThreadReuse() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger residualContextCount = new AtomicInteger(0);
            int taskCount = 100;

            for (int i = 0; i < taskCount; i++) {
                final int taskId = i;
                Link link = AriadneContext.spawn(taskId + 1, "task-payload-" + taskId);

                // Task 1: Runs with AriadneRunnable
                pool.submit(AriadneRunnable.wrap(() -> {
                    assertThat(AriadneContext.current()).isNotNull();
                    assertThat(AriadneContext.current().siteId).isEqualTo(taskId + 1);
                }, link)).get();

                // Task 2: Direct uninstrumented task executing on the SAME worker thread
                pool.submit(() -> {
                    if (AriadneContext.current() != null) {
                        residualContextCount.incrementAndGet();
                    }
                }).get();
            }

            // Zero residual contexts must exist on worker threads
            assertThat(residualContextCount.get())
                    .as("No residual AriadneContext must linger on reused worker threads")
                    .isEqualTo(0);

        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Cancelled Tasks: Tasks cancelled via Future.cancel must not leak state or poison ThreadLocals")
    void shouldHandleCancelledTasksCleanly() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);

            // 1. Submit a blocking task to saturate the single-thread pool
            pool.submit(() -> {
                started.countDown();
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // 2. Submit queued tasks with Ariadne context
            Link link = AriadneContext.spawn(500, "queued-payload");
            Future<?> futureToCancel = pool.submit(AriadneRunnable.wrap(() -> {
                // Should never execute because it gets cancelled in queue
            }, link));

            // Cancel task while waiting in queue
            boolean cancelled = futureToCancel.cancel(true);
            assertThat(cancelled).isTrue();
            assertThat(futureToCancel.isCancelled()).isTrue();

            // Unblock pool
            blocker.countDown();

            // 3. Verify next task on that thread starts with a clean ThreadLocal
            AtomicBoolean cleanThreadLocal = new AtomicBoolean(false);
            pool.submit(() -> {
                cleanThreadLocal.set(AriadneContext.current() == null);
            }).get();

            assertThat(cleanThreadLocal.get())
                    .as("ThreadLocal must be null on next task execution after a cancelled task")
                    .isTrue();

        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Nested Submits: Nested task executions must correctly unwind ThreadLocal state")
    void shouldUnwindThreadLocalOnNestedExecutions() {
        Link outerLink = AriadneContext.spawn(601, "outer");

        try (AriadneContext.Scope outerScope = AriadneContext.attach(outerLink)) {
            assertThat(AriadneContext.current()).isSameAs(outerLink);

            Link innerLink1 = AriadneContext.spawn(602, "inner-1");
            try (AriadneContext.Scope innerScope1 = AriadneContext.attach(innerLink1)) {
                assertThat(AriadneContext.current()).isSameAs(innerLink1);

                Link innerLink2 = AriadneContext.spawn(603, "inner-2");
                try (AriadneContext.Scope innerScope2 = AriadneContext.attach(innerLink2)) {
                    assertThat(AriadneContext.current()).isSameAs(innerLink2);
                }
                // Restored to innerLink1
                assertThat(AriadneContext.current()).isSameAs(innerLink1);
            }
            // Restored to outerLink
            assertThat(AriadneContext.current()).isSameAs(outerLink);
        }

        // Restored to null
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    @DisplayName("Soak Test: 100,000 continuous async hops must remain bounded in depth and stable in heap")
    void shouldMaintainStableHeapOver100000Hops() {
        int maxDepth = 32;
        AriadneConfig.setMaxDepth(maxDepth);

        // Record initial memory after GC
        System.gc();
        long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        Link root = AriadneContext.spawn(1, "root-ingress-controller");
        AriadneContext.set(root);

        int totalHops = 100_000;
        for (int hop = 2; hop <= totalHops; hop++) {
            Link next = AriadneContext.spawn(hop % 1000 + 1, "attachment-" + hop);
            AriadneContext.set(next);

            // Bounded depth guarantee: depth must NEVER exceed maxDepth
            assertThat(next.depth)
                    .as("Depth must be bounded by maxDepth (%d), but was %d at hop %d", maxDepth, next.depth, hop)
                    .isLessThanOrEqualTo(maxDepth);
        }

        // Current active link must have depth <= maxDepth
        Link active = AriadneContext.current();
        assertThat(active).isNotNull();
        assertThat(active.depth).isLessThanOrEqualTo(maxDepth);

        // Find the root of the active chain: must be the original root controller!
        Link chainRoot = active;
        while (chainRoot.parent != null) {
            chainRoot = chainRoot.parent;
        }
        assertThat(chainRoot.siteId).isEqualTo(1);
        assertThat(chainRoot.attachment).isEqualTo("root-ingress-controller");

        // Force GC and measure memory after 100,000 hops
        System.gc();
        long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryDeltaBytes = finalMemory - initialMemory;

        // Bounded footprint: with depth capped at 32, memory growth must be trivial (< 20 MB)
        assertThat(memoryDeltaBytes)
                .as("Memory delta after 100k hops must remain bounded (got %d bytes)", memoryDeltaBytes)
                .isLessThan(20 * 1024 * 1024);
    }
}
