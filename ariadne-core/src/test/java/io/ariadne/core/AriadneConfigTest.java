package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AriadneConfigTest {

    @BeforeEach
    @AfterEach
    void resetConfig() {
        AriadneConfig.resetDefaults();
    }

    @Test
    void shouldHaveSensibleProductionDefaults() {
        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(32);
        assertThat(AriadneConfig.isCanaryProbesEnabled()).isTrue();
        assertThat(AriadneConfig.isFailFast()).isFalse();
        assertThat(AriadneConfig.isJmxEnabled()).isTrue();
    }

    @Test
    void shouldDynamicallyUpdateMaxDepth() {
        AriadneConfig.setMaxDepth(48);
        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(48);

        AriadneConfig.setMaxDepth(1);
        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(1);

        AriadneConfig.setMaxDepth(1024);
        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(1024);
    }

    @Test
    void shouldRejectInvalidMaxDepthValues() {
        assertThatThrownBy(() -> AriadneConfig.setMaxDepth(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between 1 and 1024");

        assertThatThrownBy(() -> AriadneConfig.setMaxDepth(-10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AriadneConfig.setMaxDepth(1025))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDynamicallyUpdateFlags() {
        AriadneConfig.setCanaryProbesEnabled(false);
        assertThat(AriadneConfig.isCanaryProbesEnabled()).isFalse();

        AriadneConfig.setFailFast(true);
        assertThat(AriadneConfig.isFailFast()).isTrue();

        AriadneConfig.setJmxEnabled(false);
        assertThat(AriadneConfig.isJmxEnabled()).isFalse();
    }

    @Test
    void shouldResetToDefaults() {
        AriadneConfig.setMaxDepth(100);
        AriadneConfig.setCanaryProbesEnabled(false);
        AriadneConfig.setFailFast(true);
        AriadneConfig.setJmxEnabled(false);

        AriadneConfig.resetDefaults();

        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(32);
        assertThat(AriadneConfig.isCanaryProbesEnabled()).isTrue();
        assertThat(AriadneConfig.isFailFast()).isFalse();
        assertThat(AriadneConfig.isJmxEnabled()).isTrue();
    }
}
