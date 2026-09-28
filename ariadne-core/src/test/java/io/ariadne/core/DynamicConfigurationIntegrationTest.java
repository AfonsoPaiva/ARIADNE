package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicConfigurationIntegrationTest {

    @BeforeEach
    @AfterEach
    void resetAll() {
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
        AriadneContext.clear();
        SiteRegistry.clear();
    }

    @Test
    void shouldDynamicallyRespectMaxDepthAndRecordMetrics() {
        // Configure maxDepth = 4 dynamically
        AriadneConfig.setMaxDepth(4);

        int siteId = SiteRegistry.register(new CallSiteMetadata(
                "com.example.TestClass", "testMethod", "TestClass.java", 10, "Test"
        ));

        // Create a chain of 10 causal links
        Link current = null;
        for (int i = 0; i < 10; i++) {
            current = new Link(current, siteId, Thread.currentThread().threadId());
        }

        // Reconstruct synthetic exception
        AsyncCausalityException exception = AriadneReconstructor.buildSyntheticException(current);

        assertThat(exception).isNotNull();
        // Depth should be capped at 4
        assertThat(exception.getStackTrace()).hasSize(4);
        assertThat(exception.getMessage()).contains("4 hops");
        assertThat(AriadneMetrics.getReconstructionsCapped()).isEqualTo(1);

        // Dynamically reconfigure maxDepth to 8 without restarting JVM
        AriadneConfig.setMaxDepth(8);
        AsyncCausalityException exceptionExpanded = AriadneReconstructor.buildSyntheticException(current);

        assertThat(exceptionExpanded).isNotNull();
        // Depth should now expand to 8
        assertThat(exceptionExpanded.getStackTrace()).hasSize(8);
        assertThat(exceptionExpanded.getMessage()).contains("8 hops");
        assertThat(AriadneMetrics.getReconstructionsCapped()).isEqualTo(2);
    }

    @Test
    void shouldRecordMetricsOnSpawnAndEnrich() {
        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(0);
        assertThat(AriadneMetrics.getReconstructionsTotal()).isEqualTo(0);

        Link link1 = AriadneContext.spawn(101);
        Link link2 = AriadneContext.spawn(102);

        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(2);

        RuntimeException ex = new RuntimeException("Test Error");
        AriadneReconstructor.enrich(ex, link2);

        assertThat(AriadneMetrics.getReconstructionsTotal()).isEqualTo(1);
        assertThat(ex.getSuppressed()).hasSize(1);
    }
}
