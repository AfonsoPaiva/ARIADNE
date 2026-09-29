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
    private static final Map<String, Integer> BY_DESCRIPTION = new ConcurrentHashMap<>();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE
    );

    private SiteRegistry() {}

    private static final class SiteIdClassValue extends ClassValue<Integer> {
        @Override
        protected Integer computeValue(Class<?> type) {
            CallSiteMetadata metadata = extractCallSiteMetadata(type);
            if (metadata != null) {
                return register(metadata);
            }
            return 0;
        }
    }

    private static final SiteIdClassValue SITE_ID_BY_CLASS = new SiteIdClassValue();

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
            metadata.setSiteId(existing);
            return existing;
        }

        int newId = ID_GENERATOR.getAndIncrement();
        Integer previous = BY_METADATA.putIfAbsent(metadata, newId);
        if (previous != null) {
            metadata.setSiteId(previous);
            return previous;
        }
        BY_ID.put(newId, metadata);
        metadata.setSiteId(newId);
        return newId;
    }

    /**
     * Resolves or registers a call site from a lambda or Runnable/Callable class using ClassValue<Integer> caching.
     * Extracts the user's enclosing class (e.g. from OrderService$$Lambda/...) without using StackWalker,
     * delivering caller identity at near-zero cost (~1-2 ns, 0 B allocation).
     *
     * @param taskClass The runtime class of the task or lambda being dispatched
     * @param fallbackDescription Fallback description if the class is an infrastructure wrapper
     * @return Deterministic siteId for this call site
     */
    public static int getOrRegister(Class<?> taskClass, String fallbackDescription) {
        if (taskClass == null) {
            return getOrRegister(fallbackDescription);
        }
        int siteId = SITE_ID_BY_CLASS.get(taskClass);
        if (siteId > 0 && BY_ID.containsKey(siteId)) {
            return siteId;
        }
        if (siteId == 0) {
            return getOrRegister(fallbackDescription);
        }
        // If siteId was wiped by test clear/reset, restore mapping
        CallSiteMetadata metadata = extractCallSiteMetadata(taskClass);
        if (metadata != null) {
            BY_ID.put(siteId, metadata);
            return siteId;
        }
        return getOrRegister(fallbackDescription);
    }

    private static CallSiteMetadata extractCallSiteMetadata(Class<?> type) {
        String rawName = type.getName();
        if (isInfrastructureClass(rawName)) {
            return null;
        }

        // Hidden classes in JDK 15+ may have slashes, e.g. "com.example.OrderService$$Lambda/0x0000000800c01234"
        String cleanName = rawName;
        int slashIdx = cleanName.indexOf('/');
        if (slashIdx > 0) {
            cleanName = cleanName.substring(0, slashIdx);
        }

        String enclosingClass;
        String methodName = "lambda";
        int lambdaIdx = cleanName.indexOf("$$Lambda");
        if (lambdaIdx > 0) {
            enclosingClass = cleanName.substring(0, lambdaIdx);
        } else {
            int dollarIdx = cleanName.indexOf('$');
            if (dollarIdx > 0) {
                enclosingClass = cleanName.substring(0, dollarIdx);
                methodName = "dispatch";
            } else {
                enclosingClass = cleanName;
                methodName = "run";
            }
        }

        String topLevelClass = enclosingClass;
        int innerDollar = topLevelClass.indexOf('$');
        if (innerDollar > 0) {
            topLevelClass = topLevelClass.substring(0, innerDollar);
        }
        int lastDot = topLevelClass.lastIndexOf('.');
        String simpleName = (lastDot >= 0) ? topLevelClass.substring(lastDot + 1) : topLevelClass;
        String fileName = simpleName + ".java";

        return new CallSiteMetadata(
                enclosingClass,
                methodName,
                fileName,
                -1,
                enclosingClass + "." + methodName
        );
    }

    private static boolean isInfrastructureClass(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("net.bytebuddy.")
                || className.startsWith("io.ariadne.core.Ariadne")
                || className.startsWith("reactor.core.")
                || className.startsWith("io.reactivex.rxjava3.")
                || className.startsWith("org.springframework.cglib.");
    }

    /**
     * Fast-path registration for call sites known statically (e.g. agent advice, reactive scheduler hooks).
     * Avoids StackWalker and object allocation entirely — cost is one ConcurrentHashMap lookup (~5 ns, 0 B allocation)
     * after first call.
     *
     * @param description Static description of the instrumented call site
     * @return Deterministic siteId for this description
     */
    public static int getOrRegister(String description) {
        if (description == null) {
            return 0;
        }
        Integer existing = BY_DESCRIPTION.get(description);
        if (existing != null) {
            return existing;
        }
        CallSiteMetadata metadata = new CallSiteMetadata(
                "io.ariadne.agent", description, null, -1, description
        );
        int newId = register(metadata);
        BY_DESCRIPTION.putIfAbsent(description, newId);
        return newId;
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
        BY_DESCRIPTION.clear();
        ID_GENERATOR.set(1);
    }
}
