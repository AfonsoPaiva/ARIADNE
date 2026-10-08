package io.ariadne.core;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable W3C Trace Context (<a href="https://www.w3.org/TR/trace-context/">Level 1</a>)
 * value: {@code traceparent} (version, trace-id, parent-id, trace-flags) plus an optional
 * opaque {@code tracestate}.
 * <p>
 * This class is deliberately dependency-free. It only parses, validates and formats the
 * header; it does not create spans or export anything. Its purpose is to let Ariadne carry the
 * identity of an externally managed trace across thread boundaries together with the causal
 * {@link Link} chain.
 */
public final class TraceContext {

    /** Length of a version-00 {@code traceparent} header value. */
    public static final int TRACEPARENT_LENGTH = 55;

    private static final byte FLAG_SAMPLED = 0x01;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String traceId;
    private final String spanId;
    private final byte flags;
    private final String traceState;

    private TraceContext(String traceId, String spanId, byte flags, String traceState) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.flags = flags;
        this.traceState = traceState;
    }

    /**
     * Creates a context from already-validated identifiers.
     *
     * @param traceId 32 lowercase hex characters, not all zero
     * @param spanId  16 lowercase hex characters, not all zero
     * @param flags   W3C trace-flags byte (bit 0 = sampled)
     * @throws IllegalArgumentException if an identifier is malformed
     */
    public static TraceContext of(String traceId, String spanId, byte flags) {
        return of(traceId, spanId, flags, null);
    }

    /**
     * Creates a context from already-validated identifiers and an optional {@code tracestate}.
     */
    public static TraceContext of(String traceId, String spanId, byte flags, String traceState) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        if (!isValidHex(traceId, 0, 32) || isAllZero(traceId, 0, 32)) {
            throw new IllegalArgumentException("Invalid W3C trace-id: " + traceId);
        }
        if (!isValidHex(spanId, 0, 16) || isAllZero(spanId, 0, 16)) {
            throw new IllegalArgumentException("Invalid W3C parent-id: " + spanId);
        }
        return new TraceContext(traceId, spanId, flags, traceState);
    }

    /**
     * Parses a {@code traceparent} header value.
     *
     * @return the parsed context, or {@code null} if the value is not a valid {@code traceparent}.
     *         Never throws: malformed input from the network must not break the application.
     */
    public static TraceContext parse(String traceparent) {
        return parse(traceparent, null);
    }

    /**
     * Parses a {@code traceparent} header value together with an optional {@code tracestate}.
     * <p>
     * Follows the specification's forward-compatibility rules: version {@code 00} must be exactly
     * 55 characters; higher versions (other than {@code ff}) may append further {@code -}-separated
     * fields, which are ignored.
     *
     * @return the parsed context, or {@code null} if invalid
     */
    public static TraceContext parse(String traceparent, String traceState) {
        if (traceparent == null || traceparent.length() < TRACEPARENT_LENGTH) {
            return null;
        }
        // layout: vv-<32>-<16>-ff  (offsets: 0, 3, 36, 53)
        if (traceparent.charAt(2) != '-' || traceparent.charAt(35) != '-' || traceparent.charAt(52) != '-') {
            return null;
        }
        if (!isValidHex(traceparent, 0, 2)) {
            return null;
        }
        boolean versionFf = traceparent.charAt(0) == 'f' && traceparent.charAt(1) == 'f';
        if (versionFf) {
            return null;
        }
        boolean version00 = traceparent.charAt(0) == '0' && traceparent.charAt(1) == '0';
        if (traceparent.length() > TRACEPARENT_LENGTH) {
            if (version00 || traceparent.charAt(TRACEPARENT_LENGTH) != '-') {
                return null;
            }
        }
        if (!isValidHex(traceparent, 3, 32) || isAllZero(traceparent, 3, 32)) {
            return null;
        }
        if (!isValidHex(traceparent, 36, 16) || isAllZero(traceparent, 36, 16)) {
            return null;
        }
        if (!isValidHex(traceparent, 53, 2)) {
            return null;
        }
        byte flags = (byte) ((hexValue(traceparent.charAt(53)) << 4) | hexValue(traceparent.charAt(54)));
        return new TraceContext(
                traceparent.substring(3, 35),
                traceparent.substring(36, 52),
                flags,
                traceState);
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public byte flags() {
        return flags;
    }

    /** The opaque {@code tracestate} value, or {@code null} if absent. */
    public String traceState() {
        return traceState;
    }

    public boolean isSampled() {
        return (flags & FLAG_SAMPLED) != 0;
    }

    /**
     * Returns a context in the same trace with a freshly generated parent-id, as a service does
     * when it starts a new span before calling downstream.
     */
    public TraceContext withNewSpanId() {
        long random;
        do {
            random = ThreadLocalRandom.current().nextLong();
        } while (random == 0L);
        char[] out = new char[16];
        for (int i = 15; i >= 0; i--) {
            out[i] = HEX[(int) (random & 0xF)];
            random >>>= 4;
        }
        return new TraceContext(traceId, new String(out), flags, traceState);
    }

    /** Formats this context as a version-00 {@code traceparent} header value. */
    public String toTraceparent() {
        char[] out = new char[TRACEPARENT_LENGTH];
        out[0] = '0';
        out[1] = '0';
        out[2] = '-';
        traceId.getChars(0, 32, out, 3);
        out[35] = '-';
        spanId.getChars(0, 16, out, 36);
        out[52] = '-';
        out[53] = HEX[(flags >> 4) & 0xF];
        out[54] = HEX[flags & 0xF];
        return new String(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TraceContext other)) {
            return false;
        }
        return flags == other.flags
                && traceId.equals(other.traceId)
                && spanId.equals(other.spanId)
                && Objects.equals(traceState, other.traceState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceId, spanId, flags, traceState);
    }

    /** Returns the {@code traceparent} representation. */
    @Override
    public String toString() {
        return toTraceparent();
    }

    private static boolean isValidHex(CharSequence s, int offset, int length) {
        if (s.length() < offset + length) {
            return false;
        }
        for (int i = offset; i < offset + length; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(CharSequence s, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static int hexValue(char c) {
        return c <= '9' ? c - '0' : c - 'a' + 10;
    }
}

