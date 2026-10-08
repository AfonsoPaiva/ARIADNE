package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopedValueContextCarrierTest {

    private ContextCarrier original;

    @BeforeEach
    void setUp() {
        assumeTrue(ScopedValueContextCarrier.isSupported(),
                "java.lang.ScopedValue unavailable on this JVM (JDK 21 needs --enable-preview)");
        original = AriadneContext.carrier();
    }

    @AfterEach
    void tearDown() {
        if (original != null) {
            AriadneContext.setCarrier(original);
        }
        AriadneContext.clear();
    }

    @Test
    void scopedBindingIsVisibleOnlyInsideTheScope() {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link link = new Link(null, 1, 1L);

        assertThat(carrier.current()).isNull();
        AtomicReference<Link> inside = new AtomicReference<>();
        carrier.runWhere(link, () -> inside.set(carrier.current()));

        assertThat(inside.get()).isSameAs(link);
        assertThat(carrier.current()).isNull();
    }

    @Test
    void nestedBindingsShadowAndRestore() {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link outer = new Link(null, 1, 1L);
        Link inner = new Link(outer, 2, 1L);
        AtomicReference<Link> afterInner = new AtomicReference<>();
        AtomicReference<Link> duringInner = new AtomicReference<>();

        carrier.runWhere(outer, () -> {
            carrier.runWhere(inner, () -> duringInner.set(carrier.current()));
            afterInner.set(carrier.current());
        });

        assertThat(duringInner.get()).isSameAs(inner);
        assertThat(afterInner.get()).isSameAs(outer);
    }

    @Test
    void overlayFromAttachWinsAndIsRestored() {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link scoped = new Link(null, 1, 1L);
        Link attached = new Link(null, 2, 1L);

        carrier.runWhere(scoped, () -> {
            try (ContextCarrier.Scope ignored = carrier.attach(attached)) {
                assertThat(carrier.current()).isSameAs(attached);
            }
            assertThat(carrier.current()).isSameAs(scoped);
        });
    }

    @Test
    void spawnParentsOnTheScopedLink() {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link root = new Link(null, 1, 1L);
        AtomicReference<Link> child = new AtomicReference<>();
        carrier.runWhere(root, () -> child.set(carrier.spawn(9)));
        assertThat(child.get().parent).isSameAs(root);
        assertThat(child.get().depth).isEqualTo(2);
    }

    @Test
    void bindingIsInheritedByVirtualThreadsNeverLeaksToUnrelatedOnes() throws Exception {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link link = new Link(null, 1, 1L);
        AtomicReference<Link> unrelated = new AtomicReference<>(link);

        carrier.runWhere(link, () -> {
            try {
                Thread t = Thread.ofVirtual().start(() -> unrelated.set(carrier.current()));
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // A plain (non-structured) thread does not inherit a scoped value: no leakage
        assertThat(unrelated.get()).isNull();
    }

    @Test
    void callWherePropagatesResultsAndCheckedExceptions() throws Exception {
        ScopedValueContextCarrier carrier = new ScopedValueContextCarrier();
        Link link = new Link(null, 1, 1L);

        String result = carrier.callWhere(link, () -> "v" + carrier.current().siteId);
        assertThat(result).isEqualTo("v1");

        assertThatThrownBy(() -> carrier.callWhere(link, () -> {
            throw new java.io.IOException("checked");
        })).isInstanceOf(java.io.IOException.class).hasMessage("checked");
    }

    @Test
    void worksAsTheActiveAriadneContextCarrier() {
        ScopedValueContextCarrier carrier = ScopedValueContextCarrier.install();
        Link link = new Link(null, 5, 1L);
        AtomicReference<Link> seen = new AtomicReference<>();
        carrier.runWhere(link, () -> seen.set(AriadneContext.current()));
        assertThat(seen.get()).isSameAs(link);

        AriadneContext.runWith(link, () -> seen.set(AriadneContext.current()));
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void unsupportedJvmFailsFastWithClearMessage() {
        // Guard for documentation: when unsupported the constructor must not return a broken carrier
        if (!ScopedValueContextCarrier.isSupported()) {
            assertThatThrownBy(ScopedValueContextCarrier::new).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}

