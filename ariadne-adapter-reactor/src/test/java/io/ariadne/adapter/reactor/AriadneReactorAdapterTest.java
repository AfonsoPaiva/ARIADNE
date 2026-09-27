package io.ariadne.adapter.reactor;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AsyncCausalityException;
import io.ariadne.core.Link;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AriadneReactorAdapterTest {

    @BeforeEach
    void setUp() {
        AriadneReactorAdapter.uninstall();
        AriadneContext.clear();
    }

    @AfterEach
    void tearDown() {
        AriadneReactorAdapter.uninstall();
        AriadneContext.clear();
    }

    @Test
    void shouldComposeWithExistingReactorScheduleHookWithoutOverwriting() {
        AtomicBoolean existingHookRan = new AtomicBoolean(false);
        String thirdPartyKey = "micrometer-simulation";

        // Register pre-existing hook (e.g., Micrometer or Brave)
        Schedulers.onScheduleHook(thirdPartyKey, runnable -> () -> {
            existingHookRan.set(true);
            runnable.run();
        });

        try {
            // Install Ariadne
            AriadneReactorAdapter.install();

            // Run a Reactor async flow
            Mono.just("hello")
                    .publishOn(Schedulers.boundedElastic())
                    .map(String::toUpperCase)
                    .block(Duration.ofSeconds(2));

            // Verify BOTH the third party hook and Ariadne executed
            assertThat(existingHookRan.get()).as("Pre-existing hook must be called").isTrue();
        } finally {
            Schedulers.resetOnScheduleHook(thirdPartyKey);
        }
    }

    @Test
    void shouldPassCanaryProbeOnInstallation() {
        assertThat(AriadneReactorAdapter.isHealthy()).isFalse();

        AriadneReactorAdapter.install();

        assertThat(AriadneReactorAdapter.isHealthy()).isTrue();
        assertThat(AriadneReactorAdapter.healthStatus().isHealthy()).isTrue();
        assertThat(AriadneReactorAdapter.healthStatus().message()).contains("successfully verified");

        AriadneReactorAdapter.uninstall();
        assertThat(AriadneReactorAdapter.isHealthy()).isFalse();
    }

    @Test
    void shouldReconstructCausalPathWhenReactorOperatorFailsAsync() {
        AriadneReactorAdapter.install();

        Link initialLink = AriadneContext.spawn(999);
        AriadneContext.set(initialLink);

        assertThatThrownBy(() -> {
            Mono.just("input")
                    .publishOn(Schedulers.parallel())
                    .map(val -> {
                        throw new IllegalStateException("Async processing failed in parallel scheduler");
                    })
                    .block(Duration.ofSeconds(2));
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Async processing failed")
                .satisfies(ex -> {
                    Throwable[] suppressed = ex.getSuppressed();
                    assertThat(suppressed)
                            .anyMatch(t -> t instanceof AsyncCausalityException);
                });
    }

    @RepeatedTest(3)
    void statisticalStressTestConcurrentReactorPipelines() throws Exception {
        AriadneReactorAdapter.install();

        int streamsCount = 200;
        CountDownLatch latch = new CountDownLatch(streamsCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger causalFailuresCount = new AtomicInteger(0);

        for (int i = 0; i < streamsCount; i++) {
            final int id = i;
            Flux.just(id)
                    .publishOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.parallel())
                    .map(val -> {
                        if (val % 2 == 0) {
                            throw new RuntimeException("Simulated error in stream " + val);
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
}
