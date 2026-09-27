package io.ariadne.core;

/**
 * Thrown when a targeted asynchronous framework (e.g. Reactor, RxJava) is present on the
 * classpath, but its version is older than Ariadne's minimum supported contract.
 * <p>
 * Ensures Ariadne fails fast and loud with explicit diagnostics rather than silently doing nothing.
 */
public class UnsupportedFrameworkVersionException extends RuntimeException {

    private final String framework;
    private final String minimumVersion;

    public UnsupportedFrameworkVersionException(String framework, String minimumVersion, String details) {
        super(String.format("Unsupported version of %s: %s (Ariadne requires %s >= %s)",
                framework, details, framework, minimumVersion));
        this.framework = framework;
        this.minimumVersion = minimumVersion;
    }

    public String framework() {
        return framework;
    }

    public String minimumVersion() {
        return minimumVersion;
    }
}
