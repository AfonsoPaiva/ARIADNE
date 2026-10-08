package io.ariadne.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TraceContextTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void parsesSpecExample() {
        TraceContext tc = TraceContext.parse(VALID);
        assertThat(tc).isNotNull();
        assertThat(tc.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(tc.spanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(tc.flags()).isEqualTo((byte) 1);
        assertThat(tc.isSampled()).isTrue();
    }

    @Test
    void roundTripsToTraceparent() {
        assertThat(TraceContext.parse(VALID).toTraceparent()).isEqualTo(VALID);
        TraceContext unsampled = TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertThat(unsampled.isSampled()).isFalse();
        assertThat(unsampled.toTraceparent()).endsWith("-00");
    }

    @Test
    void preservesHighFlagBits() {
        TraceContext tc = TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ab");
        assertThat(tc.flags()).isEqualTo((byte) 0xab);
        assertThat(tc.toTraceparent()).endsWith("-ab");
    }

    @Test
    void rejectsMalformedInputWithoutThrowing() {
        String[] invalid = {
            null,
            "",
            "garbage",
            // all-zero ids are invalid
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            // version ff is forbidden
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            // uppercase hex is forbidden
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
            // wrong separators / non-hex
            "00_4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e473z-00f067aa0ba902b7-01",
            // version 00 must be exactly 55 chars
            VALID + "-extra",
            VALID + "x",
            // too short
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0"
        };
        for (String s : invalid) {
            assertThat(TraceContext.parse(s)).as("input: %s", s).isNull();
        }
    }

    @Test
    void acceptsFutureVersionsWithTrailingFields() {
        TraceContext tc = TraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-future");
        assertThat(tc).isNotNull();
        assertThat(tc.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // Re-serialised as version 00 (we only emit what we fully understand)
        assertThat(tc.toTraceparent()).isEqualTo(VALID);
        // trailing data must be separated by '-'
        assertThat(TraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01x")).isNull();
    }

    @Test
    void factoryValidates() {
        assertThatThrownBy(() -> TraceContext.of("short", "00f067aa0ba902b7", (byte) 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TraceContext.of("4bf92f3577b34da6a3ce929d0e0e4736", "0000000000000000", (byte) 1))
                .isInstanceOf(IllegalArgumentException.class);
        TraceContext tc = TraceContext.of("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", (byte) 1, "k=v");
        assertThat(tc.traceState()).isEqualTo("k=v");
    }

    @Test
    void withNewSpanIdKeepsTraceAndProducesValidIds() {
        TraceContext parent = TraceContext.parse(VALID);
        TraceContext child = parent.withNewSpanId();
        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.flags()).isEqualTo(parent.flags());
        assertThat(child.spanId()).hasSize(16).matches("[0-9a-f]{16}").isNotEqualTo("0000000000000000");
        assertThat(TraceContext.parse(child.toTraceparent())).isEqualTo(child);
    }

    @Test
    void equalsAndHashCode() {
        assertThat(TraceContext.parse(VALID)).isEqualTo(TraceContext.parse(VALID)).hasSameHashCodeAs(TraceContext.parse(VALID));
        assertThat(TraceContext.parse(VALID)).hasToString(VALID);
    }
}

