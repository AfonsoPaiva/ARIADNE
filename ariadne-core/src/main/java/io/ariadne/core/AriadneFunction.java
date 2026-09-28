package io.ariadne.core;

import java.util.Objects;
import java.util.function.Function;

/**
 * Execution wrapper for {@link Function} that propagates the causal {@link Link}
 * and enriches exceptions if execution fails.
 */
public final class AriadneFunction<T, R> implements Function<T, R> {

    private final Function<T, R> target;
    private final Link capturedLink;

    public AriadneFunction(Function<T, R> target) {
        this(target, AriadneContext.current());
    }

    public AriadneFunction(Function<T, R> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.capturedLink = capturedLink;
    }

    public static <T, R> Function<T, R> wrap(Function<T, R> function) {
        if (function == null || function instanceof AriadneFunction) {
            return function;
        }
        return new AriadneFunction<>(function);
    }

    public static <T, R> Function<T, R> wrap(Function<T, R> function, Link link) {
        if (function == null) {
            return null;
        }
        if (function instanceof AriadneFunction<T, R> af && af.capturedLink == link) {
            return function;
        }
        return new AriadneFunction<>(function, link);
    }

    @Override
    public R apply(T t) {
        try (AriadneContext.Scope ignored = AriadneContext.attach(capturedLink)) {
            return target.apply(t);
        } catch (Throwable ex) {
            AriadneReconstructor.enrich(ex, capturedLink);
            throw ex;
        }
    }

    public Function<T, R> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
