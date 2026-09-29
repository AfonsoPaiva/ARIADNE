package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiteRegistryTest {

    @BeforeEach
    void setUp() {
        SiteRegistry.resetForTests();
    }

    @Test
    void shouldRegisterAndRetrieveMetadata() {
        CallSiteMetadata metadata = new CallSiteMetadata(
                "com.example.OrderService",
                "submitOrder",
                "OrderService.java",
                42,
                "submit"
        );

        int siteId = SiteRegistry.register(metadata);

        assertThat(siteId).isGreaterThan(0);
        CallSiteMetadata retrieved = SiteRegistry.get(siteId);
        assertThat(retrieved).isEqualTo(metadata);
        assertThat(retrieved.className()).isEqualTo("com.example.OrderService");
        assertThat(retrieved.methodName()).isEqualTo("submitOrder");
        assertThat(retrieved.fileName()).isEqualTo("OrderService.java");
        assertThat(retrieved.lineNumber()).isEqualTo(42);
        assertThat(retrieved.description()).isEqualTo("submit");
    }

    @Test
    void shouldReuseExistingIdForDuplicateMetadata() {
        CallSiteMetadata m1 = new CallSiteMetadata("com.example.Service", "run", "Service.java", 10);
        CallSiteMetadata m2 = new CallSiteMetadata("com.example.Service", "run", "Service.java", 10);

        int id1 = SiteRegistry.register(m1);
        int id2 = SiteRegistry.register(m2);

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void shouldCaptureCurrentCallerSiteId() {
        int siteId = SiteRegistry.captureCallerSiteId(0, "test-site");

        assertThat(siteId).isGreaterThan(0);
        CallSiteMetadata metadata = SiteRegistry.get(siteId);
        assertThat(metadata).isNotNull();
        assertThat(metadata.className()).isEqualTo(SiteRegistryTest.class.getName());
        assertThat(metadata.methodName()).isEqualTo("shouldCaptureCurrentCallerSiteId");
        assertThat(metadata.description()).isEqualTo("test-site");
    }

    @Test
    void shouldCacheAndReuseIdForStaticDescription() {
        int id1 = SiteRegistry.getOrRegister("Executor.execute");
        int id2 = SiteRegistry.getOrRegister("Executor.execute");

        assertThat(id1).isGreaterThan(0);
        assertThat(id1).isEqualTo(id2);

        CallSiteMetadata metadata = SiteRegistry.get(id1);
        assertThat(metadata).isNotNull();
        assertThat(metadata.description()).isEqualTo("Executor.execute");
        assertThat(metadata.className()).isEqualTo("io.ariadne.agent");
    }

    @Test
    void shouldClearAllRegistrations() {
        int id1 = SiteRegistry.getOrRegister("Test.desc");
        assertThat(SiteRegistry.size()).isGreaterThanOrEqualTo(1);

        SiteRegistry.clear();
        assertThat(SiteRegistry.size()).isEqualTo(0);
        assertThat(SiteRegistry.get(id1)).isNull();
    }
}
