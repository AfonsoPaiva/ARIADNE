package io.ariadne.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe global registry mapping integer site IDs to CallSiteMetadata.
 * <p>
 * Ensures O(1) lock-free lookups and caches repeated call sites so that the same
 * invocation point shares a single siteId.
 */
public final class SiteRegistry {

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(1);
    private static final Map<Integer, CallSiteMetadata> BY_ID = new ConcurrentHashMap<>();
    private static final Map<CallSiteMetadata, Integer> BY_METADATA = new ConcurrentHashMap<>();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE
    );

    private SiteRegistry() {}

    /**
     * Registers call site metadata and returns a unique, deterministic siteId.
     * If the metadata has been seen before, returns the existing siteId.
     */
    public static int register(CallSiteMetadata metadata) {
        if (metadata == null) {
            return 0;
        }
        Integer existing = BY_METADATA.get(metadata);
        if (existing != null) {
            return existing;
        }

        int newId = ID_GENERATOR.getAndIncrement();
        Integer previous = BY_METADATA.putIfAbsent(metadata, newId);
        if (previous != null) {
            return previous;
        }
        BY_ID.put(newId, metadata);
        return newId;
    }

    /**
     * Fast-path registration for call sites known statically (e.g. agent advice, reactive scheduler hooks).
     * Avoids StackWalker overhead entirely — cost is one ConcurrentHashMap lookup (~5 ns) after first call.
     *
     * @param description Static description of the instrumented call site
     * @return Deterministic siteId for this description
     */
    public static int getOrRegister(String description) {
        CallSiteMetadata metadata = new CallSiteMetadata(
                "io.ariadne.agent", description, null, -1, description
        );
        Integer existing = BY_METADATA.get(metadata);
        if (existing != null) {
            return existing;
        }
        return register(metadata);
    }

    /**
     * Resolves metadata for the given siteId. Returns null if not found.
     */
    public static CallSiteMetadata get(int siteId) {
        return BY_ID.get(siteId);
    }

    /**
     * Captures the immediate calling frame outside of Ariadne infrastructure.
     * Uses StackWalker with shallow depth for minimal overhead.
     */
    public static int captureCallerSiteId(int skipFrames, String description) {
        return STACK_WALKER.walk(frames -> {
            var frameOpt = frames
                    .filter(f -> !isInternalFrame(f.getClassName()))
                    .skip(Math.max(0, skipFrames))
                    .findFirst();

            if (frameOpt.isEmpty()) {
                return 0;
            }

            StackWalker.StackFrame frame = frameOpt.get();
            CallSiteMetadata metadata = new CallSiteMetadata(
                    frame.getClassName(),
                    frame.getMethodName(),
                    frame.getFileName(),
                    frame.getLineNumber(),
                    description
            );
            return register(metadata);
        });
    }

    private static boolean isInternalFrame(String className) {
        return className.equals(SiteRegistry.class.getName())
                || className.equals(AriadneContext.class.getName())
                || className.equals(AriadneRunnable.class.getName())
                || className.equals(AriadneCallable.class.getName())
                || className.equals(AriadneReconstructor.class.getName())
                || className.startsWith("io.ariadne.agent.")
                || className.startsWith("io.ariadne.adapter.");
    }

    /**
     * Returns the total number of distinct call sites currently registered.
     */
    public static int size() {
        return BY_ID.size();
    }

    /**
     * Clears all registered call sites and resets ID generator.
     */
    public static void clear() {
        resetForTests();
    }

    /**
     * Resets registry state. Intended strictly for test environments.
     */
    static void resetForTests() {
        BY_ID.clear();
        BY_METADATA.clear();
        ID_GENERATOR.set(1);
    }
}
