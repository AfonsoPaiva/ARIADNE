package io.ariadne.adapter.rxjava;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.Link;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.MaybeObserver;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

final class AriadneRxObservers {

    private AriadneRxObservers() {}

    static <T> Observer<T> wrap(Observer<T> downstream) {
        if (downstream == null || downstream instanceof AriadneObserver) {
            return downstream;
        }
        return new AriadneObserver<>(downstream);
    }

    static <T> SingleObserver<T> wrap(SingleObserver<T> downstream) {
        if (downstream == null || downstream instanceof AriadneSingleObserver) {
            return downstream;
        }
        return new AriadneSingleObserver<>(downstream);
    }

    static <T> MaybeObserver<T> wrap(MaybeObserver<T> downstream) {
        if (downstream == null || downstream instanceof AriadneMaybeObserver) {
            return downstream;
        }
        return new AriadneMaybeObserver<>(downstream);
    }

    static CompletableObserver wrap(CompletableObserver downstream) {
        if (downstream == null || downstream instanceof AriadneCompletableObserver) {
            return downstream;
        }
        return new AriadneCompletableObserver(downstream);
    }

    static <T> Subscriber<T> wrap(Subscriber<T> downstream) {
        if (downstream == null || downstream instanceof AriadneSubscriber) {
            return downstream;
        }
        return new AriadneSubscriber<>(downstream);
    }

    private static final class AriadneObserver<T> implements Observer<T> {
        private final Observer<T> downstream;

        AriadneObserver(Observer<T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Disposable d) {
            downstream.onSubscribe(d);
        }

        @Override
        public void onNext(T t) {
            downstream.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            Link current = AriadneContext.current();
            if (current != null) {
                AriadneReconstructor.enrich(t, current);
            }
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    private static final class AriadneSingleObserver<T> implements SingleObserver<T> {
        private final SingleObserver<T> downstream;

        AriadneSingleObserver(SingleObserver<T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Disposable d) {
            downstream.onSubscribe(d);
        }

        @Override
        public void onSuccess(T t) {
            downstream.onSuccess(t);
        }

        @Override
        public void onError(Throwable t) {
            Link current = AriadneContext.current();
            if (current != null) {
                AriadneReconstructor.enrich(t, current);
            }
            downstream.onError(t);
        }
    }

    private static final class AriadneMaybeObserver<T> implements MaybeObserver<T> {
        private final MaybeObserver<T> downstream;

        AriadneMaybeObserver(MaybeObserver<T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Disposable d) {
            downstream.onSubscribe(d);
        }

        @Override
        public void onSuccess(T t) {
            downstream.onSuccess(t);
        }

        @Override
        public void onError(Throwable t) {
            Link current = AriadneContext.current();
            if (current != null) {
                AriadneReconstructor.enrich(t, current);
            }
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    private static final class AriadneCompletableObserver implements CompletableObserver {
        private final CompletableObserver downstream;

        AriadneCompletableObserver(CompletableObserver downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Disposable d) {
            downstream.onSubscribe(d);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }

        @Override
        public void onError(Throwable t) {
            Link current = AriadneContext.current();
            if (current != null) {
                AriadneReconstructor.enrich(t, current);
            }
            downstream.onError(t);
        }
    }

    private static final class AriadneSubscriber<T> implements Subscriber<T> {
        private final Subscriber<T> downstream;

        AriadneSubscriber(Subscriber<T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Subscription s) {
            downstream.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            downstream.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            Link current = AriadneContext.current();
            if (current != null) {
                AriadneReconstructor.enrich(t, current);
            }
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }
}
