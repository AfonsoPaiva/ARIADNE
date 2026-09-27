package io.ariadne.core;

/**
 * Synthetic exception representing the asynchronous causal call chain leading up to an execution.
 * <p>
 * Bypasses native {@link #fillInStackTrace()} for near-zero creation overhead.
 * Its stack trace is lazily populated from the {@link Link} chain.
 */
public final class AsyncCausalityException extends Exception {

    public AsyncCausalityException(String message) {
        super(message);
    }

    public AsyncCausalityException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // No-op: stack trace is populated explicitly from Link nodes
        return this;
    }
}
