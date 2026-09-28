package io.ariadne.core;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Execution wrapper for {@link BiFunction} that propagates the causal {@link Link}
 * and enriches exceptions if execution fails.
 */
public final class AriadneBiFunction<T, U, R> implements BiFunction<T, U, R> {

    private final BiFunction<T, U, R> target;
    private final Link capturedLink;

    public AriadneBiFunction(BiFunction<T, U, R> target) {
        this(target, AriadneContext.current());
    }

    public AriadneBiFunction(BiFunction<T, U, R> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.capturedLink = capturedLink;
    }

    @SuppressWarnings("unchecked")
    public static <T, U, R> BiFunction<T, U, R> wrap(BiFunction<T, U, R> function) {
        if (function == null || function instanceof AriadneBiFunction) {
            return function;
        }
        return new AriadneBiFunction<>(function);
    }

    @SuppressWarnings("unchecked")
    public static <T, U, R> BiFunction<T, U, R> wrap(BiFunction<T, U, R> function, Link link) {
        if (function == null) {
            return null;
        }
        if (function instanceof AriadneBiFunction<T, U, R> abf && abf.capturedLink == link) {
            return function;
        }
        return new AriadneBiFunction<>(function, link);
    }

    @Override
    public R apply(T t, U u) {
        Link previous = AriadneContext.current();
        AriadneContext.set(capturedLink);
        try {
            return target.apply(t, u);
        } catch (Throwable ex) {
            AriadneReconstructor.enrich(ex, capturedLink);
            throw ex;
        } finally {
            AriadneContext.set(previous);
        }
    }

    public BiFunction<T, U, R> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
