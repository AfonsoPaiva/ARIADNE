package io.ariadne.adapter.rxjava;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AsyncCausalityException;
import io.ariadne.core.Link;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AriadneRxJavaAdapterTest {

    @BeforeEach
    void setUp() {
        AriadneRxJavaAdapter.uninstall();
        AriadneContext.clear();
    }

    @AfterEach
    void tearDown() {
        AriadneRxJavaAdapter.uninstall();
        AriadneContext.clear();
    }

    @Test
    void shouldComposeWithExistingRxJavaScheduleHandlerWithoutOverwriting() {
        AtomicBoolean existingHandlerRan = new AtomicBoolean(false);

        // Pre-existing handler (e.g. Micrometer Context Propagation)
        RxJavaPlugins.setScheduleHandler(runnable -> () -> {
            existingHandlerRan.set(true);
            runnable.run();
        });

        // Install Ariadne
        AriadneRxJavaAdapter.install();

        // Run an RxJava async stream
        String result = Single.just("rxjava-test")
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .map(String::toUpperCase)
                .blockingGet();

        assertThat(result).isEqualTo("RXJAVA-TEST");
        assertThat(existingHandlerRan.get()).as("Pre-existing handler must have been executed").isTrue();
    }

    @Test
    void shouldPassCanaryProbeOnInstallation() {
        assertThat(AriadneRxJavaAdapter.isHealthy()).isFalse();

        AriadneRxJavaAdapter.install();

        assertThat(AriadneRxJavaAdapter.isHealthy()).isTrue();
        assertThat(AriadneRxJavaAdapter.healthStatus().isHealthy()).isTrue();
        assertThat(AriadneRxJavaAdapter.healthStatus().message()).contains("successfully verified");

        AriadneRxJavaAdapter.uninstall();
        assertThat(AriadneRxJavaAdapter.isHealthy()).isFalse();
    }

    @Test
    void shouldReconstructCausalPathWhenRxJavaStreamFailsAsync() {
        AriadneRxJavaAdapter.install();

        Link initialLink = AriadneContext.spawn(888);
        AriadneContext.set(initialLink);

        assertThatThrownBy(() -> {
            Single.just("item")
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.computation())
                    .map(val -> {
                        throw new IllegalStateException("Async computation error in RxJava");
                    })
                    .blockingGet();
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Async computation error")
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> t instanceof AsyncCausalityException);
                });
    }

    @RepeatedTest(3)
    void statisticalStressTestConcurrentRxJavaStreams() throws Exception {
        AriadneRxJavaAdapter.install();

        int streamsCount = 200;
        CountDownLatch latch = new CountDownLatch(streamsCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger causalFailuresCount = new AtomicInteger(0);

        for (int i = 0; i < streamsCount; i++) {
            final int id = i;
            Observable.just(id)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.computation())
                    .map(val -> {
                        if (val % 2 == 0) {
                            throw new RuntimeException("Simulated error in RxJava stream " + val);
                        }
                        return "success-" + val;
                    })
                    .subscribe(
                            item -> successCount.incrementAndGet(),
                            err -> {
                                for (Throwable s : err.getSuppressed()) {
                                    if (s instanceof AsyncCausalityException) {
                                        causalFailuresCount.incrementAndGet();
                                        break;
                                    }
                                }
                                latch.countDown();
                            },
                            latch::countDown
                    );
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        assertThat(successCount.get()).isEqualTo(streamsCount / 2);
        assertThat(causalFailuresCount.get()).isEqualTo(streamsCount / 2);
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void shouldRestorePreviousHandlerOnUninstall() {
        AtomicBoolean customHandlerCalled = new AtomicBoolean(false);

        RxJavaPlugins.setScheduleHandler(runnable -> () -> {
            customHandlerCalled.set(true);
            runnable.run();
        });

        AriadneRxJavaAdapter.install();
        assertThat(AriadneRxJavaAdapter.isInstalled()).isTrue();

        AriadneRxJavaAdapter.uninstall();
        assertThat(AriadneRxJavaAdapter.isInstalled()).isFalse();

        // Run a task and ensure our original handler is still there
        Single.just("ping")
                .subscribeOn(Schedulers.io())
                .blockingGet();

        assertThat(customHandlerCalled.get()).isTrue();
    }
}
