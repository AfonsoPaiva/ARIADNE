package io.ariadne.core;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Execution wrapper for {@link Consumer} that propagates the causal {@link Link}
 * and enriches exceptions if execution fails.
 */
public final class AriadneConsumer<T> implements Consumer<T> {

    private final Consumer<T> target;
    private final Link capturedLink;

    public AriadneConsumer(Consumer<T> target) {
        this(target, AriadneContext.current());
    }

    public AriadneConsumer(Consumer<T> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.capturedLink = capturedLink;
    }

    public static <T> Consumer<T> wrap(Consumer<T> consumer) {
        if (consumer == null || consumer instanceof AriadneConsumer) {
            return consumer;
        }
        return new AriadneConsumer<>(consumer);
    }

    public static <T> Consumer<T> wrap(Consumer<T> consumer, Link link) {
        if (consumer == null) {
            return null;
        }
        if (consumer instanceof AriadneConsumer<T> ac && ac.capturedLink == link) {
            return consumer;
        }
        return new AriadneConsumer<>(consumer, link);
    }

    @Override
    public void accept(T t) {
        Link previous = AriadneContext.current();
        AriadneContext.set(capturedLink);
        try {
            target.accept(t);
        } catch (Throwable ex) {
            AriadneReconstructor.enrich(ex, capturedLink);
            throw ex;
        } finally {
            AriadneContext.set(previous);
        }
    }

    public Consumer<T> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
