package io.ariadne.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
}
