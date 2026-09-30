package io.ariadne.adapter.rxjava;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AsyncCausalityException;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.Link;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeObserver;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

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

    @Test
    void shouldPropagateAndEnrichFlowableStreams() {
        AriadneRxJavaAdapter.install();

        // 1. Success path
        String success = Flowable.just("flowable-item")
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .blockingFirst();
        assertThat(success).isEqualTo("flowable-item");

        // 2. Failure path with active causality
        Link initialLink = AriadneContext.spawn(101);
        AriadneContext.set(initialLink);

        assertThatThrownBy(() -> {
            Flowable.<String>error(new IllegalStateException("Flowable error"))
                    .subscribeOn(Schedulers.io())
                    .blockingFirst();
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flowable error")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed())
                            .anyMatch(t -> t instanceof AsyncCausalityException);
                });
    }

    @Test
    void shouldPropagateAndEnrichMaybeStreams() {
        AriadneRxJavaAdapter.install();

        // 1. Success value
        String val = Maybe.just("maybe-val")
                .subscribeOn(Schedulers.io())
                .blockingGet();
        assertThat(val).isEqualTo("maybe-val");

        // 2. Empty onComplete
        String empty = Maybe.<String>empty()
                .subscribeOn(Schedulers.io())
                .blockingGet();
        assertThat(empty).isNull();

        // 3. Failure path with causality
        Link initialLink = AriadneContext.spawn(102);
        AriadneContext.set(initialLink);

        assertThatThrownBy(() -> {
            Maybe.<String>error(new IllegalStateException("Maybe error"))
                    .subscribeOn(Schedulers.io())
                    .blockingGet();
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maybe error")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed())
                            .anyMatch(t -> t instanceof AsyncCausalityException);
                });
    }

    @Test
    void shouldPropagateAndEnrichCompletableStreams() {
        AriadneRxJavaAdapter.install();

        // 1. Success onComplete
        Completable.complete()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .blockingAwait();

        // 2. Failure path with causality
        Link initialLink = AriadneContext.spawn(103);
        AriadneContext.set(initialLink);

        assertThatThrownBy(() -> {
            Completable.error(new IllegalStateException("Completable error"))
                    .subscribeOn(Schedulers.io())
                    .blockingAwait();
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Completable error")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed())
                            .anyMatch(t -> t instanceof AsyncCausalityException);
                });
    }

    @Test
    void shouldHandleErrorsWithoutActiveContextGracefully() {
        AriadneRxJavaAdapter.install();
        AriadneContext.clear();

        assertThatThrownBy(() -> Single.error(new RuntimeException("no-context")).blockingGet())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> Observable.error(new RuntimeException("no-context")).blockingFirst())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> Maybe.error(new RuntimeException("no-context")).blockingGet())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> Completable.error(new RuntimeException("no-context")).blockingAwait())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> Flowable.error(new RuntimeException("no-context")).blockingFirst())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldComposeWithAllExistingSubscribeHooks() {
        AtomicBoolean obsHook = new AtomicBoolean(false);
        AtomicBoolean singleHook = new AtomicBoolean(false);
        AtomicBoolean maybeHook = new AtomicBoolean(false);
        AtomicBoolean compHook = new AtomicBoolean(false);
        AtomicBoolean flowHook = new AtomicBoolean(false);

        RxJavaPlugins.setOnObservableSubscribe((obs, observer) -> {
            obsHook.set(true);
            return observer;
        });
        RxJavaPlugins.setOnSingleSubscribe((single, observer) -> {
            singleHook.set(true);
            return observer;
        });
        RxJavaPlugins.setOnMaybeSubscribe((maybe, observer) -> {
            maybeHook.set(true);
            return observer;
        });
        RxJavaPlugins.setOnCompletableSubscribe((comp, observer) -> {
            compHook.set(true);
            return observer;
        });
        RxJavaPlugins.setOnFlowableSubscribe((flow, subscriber) -> {
            flowHook.set(true);
            return subscriber;
        });

        AriadneRxJavaAdapter.install();

        Observable.just("obs").blockingFirst();
        Single.just("single").blockingGet();
        Maybe.just("maybe").blockingGet();
        Completable.complete().blockingAwait();
        Flowable.just("flow").blockingFirst();

        assertThat(obsHook.get()).isTrue();
        assertThat(singleHook.get()).isTrue();
        assertThat(maybeHook.get()).isTrue();
        assertThat(compHook.get()).isTrue();
        assertThat(flowHook.get()).isTrue();
    }

    @Test
    void shouldVerifyObserverWrappersNullAndIdempotence() {
        // Null checks
        assertThat(AriadneRxObservers.wrap((Observer<?>) null)).isNull();
        assertThat(AriadneRxObservers.wrap((SingleObserver<?>) null)).isNull();
        assertThat(AriadneRxObservers.wrap((MaybeObserver<?>) null)).isNull();
        assertThat(AriadneRxObservers.wrap((CompletableObserver) null)).isNull();
        assertThat(AriadneRxObservers.wrap((Subscriber<?>) null)).isNull();

        // Idempotence checks
        Observer<Object> obs = AriadneRxObservers.wrap(new io.reactivex.rxjava3.observers.DefaultObserver<>() {
            @Override public void onNext(Object o) {}
            @Override public void onError(Throwable e) {}
            @Override public void onComplete() {}
        });
        assertThat(AriadneRxObservers.wrap(obs)).isSameAs(obs);

        SingleObserver<Object> single = AriadneRxObservers.wrap(new io.reactivex.rxjava3.observers.DisposableSingleObserver<>() {
            @Override public void onSuccess(Object o) {}
            @Override public void onError(Throwable e) {}
        });
        assertThat(AriadneRxObservers.wrap(single)).isSameAs(single);

        MaybeObserver<Object> maybe = AriadneRxObservers.wrap(new io.reactivex.rxjava3.observers.DisposableMaybeObserver<>() {
            @Override public void onSuccess(Object o) {}
            @Override public void onError(Throwable e) {}
            @Override public void onComplete() {}
        });
        assertThat(AriadneRxObservers.wrap(maybe)).isSameAs(maybe);

        CompletableObserver comp = AriadneRxObservers.wrap(new io.reactivex.rxjava3.observers.DisposableCompletableObserver() {
            @Override public void onComplete() {}
            @Override public void onError(Throwable e) {}
        });
        assertThat(AriadneRxObservers.wrap(comp)).isSameAs(comp);

        Subscriber<Object> sub = AriadneRxObservers.wrap(new io.reactivex.rxjava3.subscribers.DefaultSubscriber<>() {
            @Override public void onNext(Object o) {}
            @Override public void onError(Throwable e) {}
            @Override public void onComplete() {}
        });
        assertThat(AriadneRxObservers.wrap(sub)).isSameAs(sub);
    }

    @Test
    void shouldHandleAdapterEdgeCases() throws Exception {
        // Multiple install calls (idempotence)
        AriadneRxJavaAdapter.install();
        AriadneRxJavaAdapter.install();
        assertThat(AriadneRxJavaAdapter.isInstalled()).isTrue();

        // Multiple uninstall calls (idempotence)
        AriadneRxJavaAdapter.uninstall();
        AriadneRxJavaAdapter.uninstall();
        assertThat(AriadneRxJavaAdapter.isInstalled()).isFalse();

        // Canary probe when uninstalled
        CanaryProbeResult probe = AriadneRxJavaAdapter.runCanaryProbe();
        assertThat(probe.isHealthy()).isFalse();
        assertThat(probe.message()).contains("not installed");

        // Reflection coverage on private constructors of utility classes
        Constructor<AriadneRxJavaAdapter> adapterCtor = AriadneRxJavaAdapter.class.getDeclaredConstructor();
        adapterCtor.setAccessible(true);
        assertThat(adapterCtor.newInstance()).isNotNull();

        Constructor<AriadneRxObservers> observersCtor = AriadneRxObservers.class.getDeclaredConstructor();
        observersCtor.setAccessible(true);
        assertThat(observersCtor.newInstance()).isNotNull();
    }
}
