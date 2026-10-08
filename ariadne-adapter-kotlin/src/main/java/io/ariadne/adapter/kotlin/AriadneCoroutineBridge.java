package io.ariadne.adapter.kotlin;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElement;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;

/**
 * Entry point for propagating Ariadne's causal chain through Kotlin coroutines.
 * <p>
 * Add the element to the coroutine context where the work is launched; it then follows the
 * coroutine across every suspension and dispatcher switch:
 * <pre>{@code
 * scope.launch(Dispatchers.Default + AriadneCoroutineBridge.asElement()) {
 *     delay(10)          // resumes on another thread: the causal link is still active
 *     process()
 * }
 * }</pre>
 * <p>
 * This module has no compile-scope Kotlin dependency: {@code kotlinx-coroutines-core} is
 * {@code provided}, so it is only needed (and only loaded) by applications that use this bridge.
 */
public final class AriadneCoroutineBridge {

    private static final String SITE_DESCRIPTION = "kotlin.coroutine";

    private AriadneCoroutineBridge() {}

    /**
     * Creates an element that records one asynchronous hop (at a static, allocation-free call
     * site) rooted at the link currently active on the calling thread, and propagates it.
     * Cost: one {@link Link} allocation (40 B) and one map lookup.
     */
    public static ThreadContextElement<AriadneContext.Scope> asElement() {
        return new AriadneContextElement(AriadneContext.spawn(SiteRegistry.getOrRegister(SITE_DESCRIPTION)));
    }

    /**
     * Like {@link #asElement()}, but resolves the real caller frame (class, method, line) so the
     * synthetic stack trace points at the exact launch site. Walks the stack, so use it where the
     * extra precision is worth a few microseconds per launch.
     */
    public static ThreadContextElement<AriadneContext.Scope> asElementWithCallSite() {
        return new AriadneContextElement(
                AriadneContext.spawn(SiteRegistry.captureCallerSiteId(SITE_DESCRIPTION)));
    }

    /**
     * Creates an element that propagates exactly {@code link}, without recording a new hop.
     * Pass {@code null} to run the coroutine with no active causal link.
     */
    public static ThreadContextElement<AriadneContext.Scope> asElement(Link link) {
        return new AriadneContextElement(link);
    }

    /**
     * Returns the {@link Link} the given coroutine context will install, or {@code null} if it
     * carries no Ariadne element.
     */
    public static Link linkOf(CoroutineContext context) {
        AriadneContextElement element = context.get(AriadneContextElement.KEY);
        return element == null ? null : element.link();
    }

    /**
     * Enriches {@code failure} with the asynchronous path carried by {@code context}. Intended for a
     * {@code CoroutineExceptionHandler}, which receives the failing coroutine's context:
     * <pre>{@code
     * CoroutineExceptionHandler { ctx, e ->
     *     AriadneCoroutineBridge.enrich(ctx, e)
     *     log.error("coroutine failed", e)
     * }
     * }</pre>
     * Idempotent and fail-safe.
     */
    public static void enrich(CoroutineContext context, Throwable failure) {
        AriadneReconstructor.enrich(failure, linkOf(context));
    }
}

