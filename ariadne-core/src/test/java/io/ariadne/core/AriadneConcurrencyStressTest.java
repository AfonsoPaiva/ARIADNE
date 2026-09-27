package io.ariadne.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AriadneConcurrencyStressTest {

    @AfterEach
    void tearDown() {
        AriadneContext.clear();
    }

    @RepeatedTest(5)
    void stressTestPlatformThreadPoolReuseNoLeaks() throws Exception {
        int tasksCount = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger leaksDetected = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>(tasksCount);

            for (int i = 0; i < tasksCount; i++) {
                final int siteId = i + 1;
                futures.add(pool.submit(() -> {
                    // Pre-check: ThreadLocal must be empty before work starts
                    if (AriadneContext.current() != null) {
                        leaksDetected.incrementAndGet();
                    }

                    // Install link and execute
                    Link taskLink = new Link(null, siteId, Thread.currentThread().threadId());
                    AriadneRunnable.wrap(() -> {
                        Link current = AriadneContext.current();
                        if (current == null || current.siteId != siteId) {
                            leaksDetected.incrementAndGet();
                        }
                    }, taskLink).run();

                    // Post-check: ThreadLocal must be cleaned up after AriadneRunnable finishes
                    if (AriadneContext.current() != null) {
                        leaksDetected.incrementAndGet();
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertThat(leaksDetected.get()).as("Context leaks detected across thread reuse").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stressTestVirtualThreadsCausalFidelityUnderLoad() throws Exception {
        int tasksCount = 1_000;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureEnrichedCount = new AtomicInteger(0);

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasksCount);

            for (int i = 0; i < tasksCount; i++) {
                final int taskId = i;
                final int site1 = SiteRegistry.register(new CallSiteMetadata("VirtualWorker", "step1", "VirtualWorker.java", 10));
                final int site2 = SiteRegistry.register(new CallSiteMetadata("VirtualWorker", "step2", "VirtualWorker.java", 20));

                futures.add(virtualExecutor.submit(() -> {
                    Link hop1 = new Link(null, site1, Thread.currentThread().threadId());
                    Link hop2 = new Link(hop1, site2, Thread.currentThread().threadId());

                    boolean shouldFail = (taskId % 2 == 0);

                    Runnable work = AriadneRunnable.wrap(() -> {
                        Link current = AriadneContext.current();
                        assertThat(current).isNotNull();
                        assertThat(current.siteId).isEqualTo(site2);
                        assertThat(current.parent.siteId).isEqualTo(site1);

                        if (shouldFail) {
                            throw new RuntimeException("Virtual thread error in task " + taskId);
                        }
                    }, hop2);

                    try {
                        work.run();
                        successCount.incrementAndGet();
                    } catch (RuntimeException ex) {
                        Throwable[] suppressed = ex.getSuppressed();
                        if (suppressed.length == 1 && suppressed[0] instanceof AsyncCausalityException ace) {
                            if (ace.getStackTrace().length == 2) {
                                failureEnrichedCount.incrementAndGet();
                            }
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertThat(successCount.get()).isEqualTo(tasksCount / 2);
            assertThat(failureEnrichedCount.get()).isEqualTo(tasksCount / 2);
        }
    }
}
