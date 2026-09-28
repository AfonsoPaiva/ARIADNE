package io.ariadne.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AriadneFunctionalWrappersTest {

    @BeforeEach
    void setUp() {
        SiteRegistry.resetForTests();
        AriadneContext.clear();
    }

    @AfterEach
    void tearDown() {
        AriadneContext.clear();
    }

    @Test
    void supplierShouldPropagateLinkAndEnrichException() {
        int site = SiteRegistry.register(new CallSiteMetadata("Test", "supplier", "Test.java", 10));
        Link link = new Link(null, site, 1L);

        Supplier<String> successSupplier = AriadneSupplier.wrap(() -> {
            assertThat(AriadneContext.current()).isSameAs(link);
            return "ok";
        }, link);
        assertThat(successSupplier.get()).isEqualTo("ok");
        assertThat(AriadneContext.current()).isNull();

        Supplier<String> failSupplier = AriadneSupplier.wrap(() -> {
            throw new IllegalStateException("Supplier failed");
        }, link);

        assertThatThrownBy(failSupplier::get)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).anyMatch(t -> t instanceof AsyncCausalityException));
    }

    @Test
    void functionShouldPropagateLinkAndEnrichException() {
        int site = SiteRegistry.register(new CallSiteMetadata("Test", "fn", "Test.java", 20));
        Link link = new Link(null, site, 2L);

        Function<String, Integer> fn = AriadneFunction.wrap(s -> {
            assertThat(AriadneContext.current()).isSameAs(link);
            return s.length();
        }, link);
        assertThat(fn.apply("test")).isEqualTo(4);

        Function<String, Integer> failFn = AriadneFunction.wrap(s -> {
            throw new IllegalArgumentException("Function failed for " + s);
        }, link);

        assertThatThrownBy(() -> failFn.apply("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).anyMatch(t -> t instanceof AsyncCausalityException));
    }

    @Test
    void consumerShouldPropagateLinkAndEnrichException() {
        int site = SiteRegistry.register(new CallSiteMetadata("Test", "consumer", "Test.java", 30));
        Link link = new Link(null, site, 3L);
        AtomicBoolean ran = new AtomicBoolean(false);

        Consumer<String> consumer = AriadneConsumer.wrap(s -> {
            assertThat(AriadneContext.current()).isSameAs(link);
            ran.set(true);
        }, link);
        consumer.accept("hello");
        assertThat(ran.get()).isTrue();

        Consumer<String> failConsumer = AriadneConsumer.wrap(s -> {
            throw new RuntimeException("Consumer error");
        }, link);

        assertThatThrownBy(() -> failConsumer.accept("err"))
                .isInstanceOf(RuntimeException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).anyMatch(t -> t instanceof AsyncCausalityException));
    }

    @Test
    void biFunctionAndBiConsumerShouldPropagateAndEnrich() {
        int site = SiteRegistry.register(new CallSiteMetadata("Test", "bi", "Test.java", 40));
        Link link = new Link(null, site, 4L);

        BiFunction<Integer, Integer, Integer> sum = AriadneBiFunction.wrap((a, b) -> {
            assertThat(AriadneContext.current()).isSameAs(link);
            return a + b;
        }, link);
        assertThat(sum.apply(2, 3)).isEqualTo(5);

        BiConsumer<String, String> biConsumer = AriadneBiConsumer.wrap((k, v) -> {
            throw new RuntimeException("BiConsumer error: " + k);
        }, link);

        assertThatThrownBy(() -> biConsumer.accept("key", "val"))
                .isInstanceOf(RuntimeException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).anyMatch(t -> t instanceof AsyncCausalityException));
    }

    @Test
    void shouldPreventRedundantWrapping() {
        Supplier<String> raw = () -> "abc";
        Supplier<String> wrapped = AriadneSupplier.wrap(raw);
        assertThat(AriadneSupplier.wrap(wrapped)).isSameAs(wrapped);

        Function<Integer, Integer> rawFn = x -> x * 2;
        Function<Integer, Integer> wrappedFn = AriadneFunction.wrap(rawFn);
        assertThat(AriadneFunction.wrap(wrappedFn)).isSameAs(wrappedFn);
    }
}
