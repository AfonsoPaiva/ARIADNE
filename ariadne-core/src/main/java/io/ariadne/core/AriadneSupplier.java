package io.ariadne.core;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Execution wrapper for {@link Supplier} that propagates the causal {@link Link}
 * and enriches exceptions if execution fails.
 */
public final class AriadneSupplier<T> implements Supplier<T> {

    private final Supplier<T> target;
    private final Link capturedLink;

    public AriadneSupplier(Supplier<T> target) {
        this(target, AriadneContext.current());
    }

    public AriadneSupplier(Supplier<T> target, Link capturedLink) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.capturedLink = capturedLink;
    }

    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        if (supplier == null || supplier instanceof AriadneSupplier) {
            return supplier;
        }
        return new AriadneSupplier<>(supplier);
    }

    public static <T> Supplier<T> wrap(Supplier<T> supplier, Link link) {
        if (supplier == null) {
            return null;
        }
        if (supplier instanceof AriadneSupplier<T> as && as.capturedLink == link) {
            return supplier;
        }
        return new AriadneSupplier<>(supplier, link);
    }

    @Override
    public T get() {
        try (AriadneContext.Scope ignored = AriadneContext.attach(capturedLink)) {
            return target.get();
        } catch (Throwable t) {
            AriadneReconstructor.enrich(t, capturedLink);
            throw t;
        }
    }

    public Supplier<T> unwrap() {
        return target;
    }

    public Link capturedLink() {
        return capturedLink;
    }
}
