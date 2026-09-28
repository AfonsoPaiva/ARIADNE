package io.ariadne.core;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Execution wrapper for {@link BiConsumer} that propagates the causal {@link Link}
 * and enriches exceptions if execution fails.
 */
public final class AriadneBiConsumer<T, U> implements BiConsumer<T, U> {

    private final BiConsumer<T, U> target;
    private final Link capturedLink;

    public AriadneBiConsumer(BiConsumer<T, U> target) {
        this(target, AriadneContext.current());
    }

    public AriadneBiConsumer(BiConsumer<T, U> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.capturedLink = capturedLink;
    }

    public static <T, U> BiConsumer<T, U> wrap(BiConsumer<T, U> consumer) {
        if (consumer == null || consumer instanceof AriadneBiConsumer) {
            return consumer;
        }
        return new AriadneBiConsumer<>(consumer);
    }

    public static <T, U> BiConsumer<T, U> wrap(BiConsumer<T, U> consumer, Link link) {
        if (consumer == null) {
            return null;
        }
        if (consumer instanceof AriadneBiConsumer<T, U> abc && abc.capturedLink == link) {
            return consumer;
        }
        return new AriadneBiConsumer<>(consumer, link);
    }

    @Override
    public void accept(T t, U u) {
        Link previous = AriadneContext.current();
        AriadneContext.set(capturedLink);
        try {
            target.accept(t, u);
        } catch (Throwable ex) {
            AriadneReconstructor.enrich(ex, capturedLink);
            throw ex;
        } finally {
            AriadneContext.set(previous);
        }
    }

    public BiConsumer<T, U> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
