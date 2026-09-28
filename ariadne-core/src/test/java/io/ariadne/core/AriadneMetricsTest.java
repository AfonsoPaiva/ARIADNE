package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AriadneMetricsTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AriadneMetrics.reset();
    }

    @Test
    void shouldIncrementHopsSpawned() {
        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(0);

        AriadneMetrics.recordHop();
        AriadneMetrics.recordHop();
        AriadneMetrics.recordHop();

        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(3);
    }

    @Test
    void shouldIncrementReconstructions() {
        assertThat(AriadneMetrics.getReconstructionsTotal()).isEqualTo(0);
        assertThat(AriadneMetrics.getReconstructionsCapped()).isEqualTo(0);

        AriadneMetrics.recordReconstruction();
        AriadneMetrics.recordReconstructionCapped();

        assertThat(AriadneMetrics.getReconstructionsTotal()).isEqualTo(1);
        assertThat(AriadneMetrics.getReconstructionsCapped()).isEqualTo(1);
    }

    @Test
    void shouldTrackCanaryProbeResults() {
        CanaryProbeResult success = CanaryProbeResult.success("Project Reactor");
        CanaryProbeResult failure = CanaryProbeResult.failure("RxJava 3", "Hook bypassed");

        AriadneMetrics.recordCanaryResult("Project Reactor", success);
        AriadneMetrics.recordCanaryResult("RxJava 3", failure);

        assertThat(AriadneMetrics.getCanaryResults()).hasSize(2);
        assertThat(AriadneMetrics.getCanaryResults().get("Project Reactor").isHealthy()).isTrue();
        assertThat(AriadneMetrics.getCanaryResults().get("RxJava 3").isHealthy()).isFalse();
    }

    @Test
    void shouldResetAllCounters() {
        AriadneMetrics.recordHop();
        AriadneMetrics.recordReconstruction();
        AriadneMetrics.recordReconstructionCapped();
        AriadneMetrics.recordCanaryResult("Test", CanaryProbeResult.success("Test"));

        AriadneMetrics.reset();

        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(0);
        assertThat(AriadneMetrics.getReconstructionsTotal()).isEqualTo(0);
        assertThat(AriadneMetrics.getReconstructionsCapped()).isEqualTo(0);
        assertThat(AriadneMetrics.getCanaryResults()).isEmpty();
    }
}
