package io.ariadne.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AriadneContextTest {

    @AfterEach
    void tearDown() {
        AriadneContext.clear();
    }

    @Test
    void shouldStoreAndRetrieveLinkOnCurrentThread() {
        assertThat(AriadneContext.current()).isNull();

        Link link = new Link(null, 10, Thread.currentThread().threadId());
        AriadneContext.set(link);

        assertThat(AriadneContext.current()).isSameAs(link);

        AriadneContext.clear();
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void shouldRestoreContextWhenUsingScope() {
        Link root = new Link(null, 1, 100L);
        Link child = new Link(root, 2, 101L);

        AriadneContext.set(root);

        try (AriadneContext.Scope scope = AriadneContext.attach(child)) {
            assertThat(AriadneContext.current()).isSameAs(child);
        }

        assertThat(AriadneContext.current()).isSameAs(root);
    }

    @Test
    void shouldSpawnChildFromCurrentContext() {
        Link parent = new Link(null, 1, 10L);
        AriadneContext.set(parent);

        Link spawned = AriadneContext.spawn(55);

        assertThat(spawned.parent).isSameAs(parent);
        assertThat(spawned.siteId).isEqualTo(55);
        assertThat(spawned.threadId).isEqualTo(Thread.currentThread().threadId());
    }

    @Test
    void shouldMaintainContextIsolationAcrossThreads() throws Exception {
        Link mainLink = new Link(null, 1, Thread.currentThread().threadId());
        AriadneContext.set(mainLink);

        AtomicReference<Link> workerThreadLink = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread = new Thread(() -> {
            workerThreadLink.set(AriadneContext.current());
            latch.countDown();
        });
        thread.start();
        latch.await(2, TimeUnit.SECONDS);

        assertThat(workerThreadLink.get()).isNull();
        assertThat(AriadneContext.current()).isSameAs(mainLink);
    }

    @Test
    void shouldNotLeakAcrossThreadPoolReuse() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Link task1Link = new Link(null, 1, 10L);

            // Task 1 executes with a link and completes
            executor.submit(() -> {
                AriadneRunnable.wrap(() -> {
                    // Task 1 work
                }, task1Link).run();
            }).get();

            // Task 2 executes on the same thread without explicit wrapper
            Future<Link> task2Context = executor.submit(AriadneContext::current);
            assertThat(task2Context.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCapChainDepthAtMaxDepthToPreventMemoryRetentionLeaks() {
        int maxDepth = AriadneConfig.getMaxDepth();
        AriadneMetrics.reset();

        Link current = new Link(null, 1, Thread.currentThread().threadId());
        AriadneContext.set(current);

        // Spawn until reaching maxDepth
        for (int i = 1; i < maxDepth; i++) {
            current = AriadneContext.spawn(i);
            AriadneContext.set(current);
            assertThat(current.depth()).isEqualTo(i + 1);
            assertThat(current.parent).isNotNull();
        }

        assertThat(current.depth()).isEqualTo(maxDepth);
        long initialCapped = AriadneMetrics.getHopsCapped();

        // The next spawn must prune oldest hops (sliding window) to prevent unbounded chain retention
        Link cappedChild = AriadneContext.spawn(999);
        int expectedRetainedDepth = (maxDepth / 2) + 1; // 16 retained ancestors + 1 new child = 17
        assertThat(cappedChild.parent).isNotNull();
        assertThat(cappedChild.depth()).isEqualTo(expectedRetainedDepth);
        assertThat(AriadneMetrics.getHopsCapped()).isEqualTo(initialCapped + 1);

        // Verify the oldest ancestor in the retained chain has parent == null
        Link ancestor = cappedChild;
        while (ancestor.parent != null) {
            ancestor = ancestor.parent;
        }
        assertThat(ancestor.depth()).isEqualTo(1);
    }
}
