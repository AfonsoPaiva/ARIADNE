package io.ariadne.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AriadneReconstructorTest {

    @BeforeEach
    void setUp() {
        SiteRegistry.resetForTests();
    }

    @Test
    void shouldReconstructAsyncStackTraceAndAttachAsSuppressed() {
        int site1 = SiteRegistry.register(new CallSiteMetadata(
                "com.example.OrderController", "createOrder", "OrderController.java", 45
        ));
        int site2 = SiteRegistry.register(new CallSiteMetadata(
                "com.example.OrderService", "processPayment", "OrderService.java", 110
        ));

        Link hop1 = new Link(null, site1, 100L);
        Link hop2 = new Link(hop1, site2, 101L);

        RuntimeException original = new RuntimeException("Payment service down");

        AriadneReconstructor.enrich(original, hop2);

        Throwable[] suppressed = original.getSuppressed();
        assertThat(suppressed).hasSize(1);
        assertThat(suppressed[0]).isInstanceOf(AsyncCausalityException.class);

        AsyncCausalityException asyncTrace = (AsyncCausalityException) suppressed[0];
        assertThat(asyncTrace.getMessage()).contains("2 hops");

        StackTraceElement[] elements = asyncTrace.getStackTrace();
        assertThat(elements).hasSize(2);

        assertThat(elements[0].getClassName()).isEqualTo("com.example.OrderService");
        assertThat(elements[0].getMethodName()).isEqualTo("processPayment");
        assertThat(elements[0].getFileName()).isEqualTo("OrderService.java");
        assertThat(elements[0].getLineNumber()).isEqualTo(110);

        assertThat(elements[1].getClassName()).isEqualTo("com.example.OrderController");
        assertThat(elements[1].getMethodName()).isEqualTo("createOrder");
        assertThat(elements[1].getFileName()).isEqualTo("OrderController.java");
        assertThat(elements[1].getLineNumber()).isEqualTo(45);
    }

    @Test
    void shouldNotAddDuplicateEnrichment() {
        Link link = new Link(null, 1, 10L);
        RuntimeException original = new RuntimeException("Error");

        AriadneReconstructor.enrich(original, link);
        AriadneReconstructor.enrich(original, link);

        assertThat(original.getSuppressed()).hasSize(1);
    }

    @Test
    void shouldGracefullyHandleNullInputs() {
        AriadneReconstructor.enrich(null, null);
        AriadneReconstructor.enrich(new RuntimeException(), null);
        AriadneReconstructor.enrich(null, new Link(null, 1, 1L));
        // Expect no exceptions thrown
    }
}
