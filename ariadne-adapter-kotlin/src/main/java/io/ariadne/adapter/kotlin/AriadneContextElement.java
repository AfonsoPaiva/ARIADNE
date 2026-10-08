package io.ariadne.adapter.kotlin;

import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElement;

import io.ariadne.core.AriadneContext;
import io.ariadne.core.Link;

/**
 * Coroutine context element that installs a causal {@link Link} on whichever thread the
 * coroutine is currently running on, and removes it again when the coroutine suspends.
 * <p>
 * Kotlin dispatchers resume a coroutine on arbitrary pool threads without going through
 * {@code Executor.execute}, so a plain thread-local would silently lose the causal chain at every
 * suspension point (and could leak it into an unrelated coroutine sharing the thread). A
 * {@link ThreadContextElement} is the sanctioned hook: {@code kotlinx.coroutines} calls
 * {@link #updateThreadContext} immediately before running the coroutine on a thread and
 * {@link #restoreThreadContext} immediately after, on that same thread.
 */
final class AriadneContextElement extends AbstractCoroutineContextElement
        implements ThreadContextElement<AriadneContext.Scope> {

    /** The context key; at most one Ariadne element is active in a coroutine context. */
    static final ElementKey KEY = new ElementKey();

    private final Link link;

    AriadneContextElement(Link link) {
        super(KEY);
        this.link = link;
    }

    Link link() {
        return link;
    }

    @Override
    public AriadneContext.Scope updateThreadContext(CoroutineContext context) {
        return AriadneContext.attach(link);
    }

    @Override
    public void restoreThreadContext(CoroutineContext context, AriadneContext.Scope oldState) {
        oldState.close();
    }

    @Override
    public String toString() {
        return "AriadneContextElement(" + link + ")";
    }

    /** ElementKey identifying {@link AriadneContextElement} in a {@link CoroutineContext}. */
    static final class ElementKey implements CoroutineContext.Key<AriadneContextElement> {
        private ElementKey() {}
    }
}
