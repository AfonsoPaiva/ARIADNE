package io.ariadne.adapter.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.Link;

class AriadneCoroutineBridgeTest {

    @BeforeEach
    void setUp() {
        AriadneContext.clear();
    }

    @AfterEach
    void tearDown() {
        AriadneContext.clear();
        AriadneConfig.resetDefaults();
    }

    @Test
    void updateAndRestoreAttachAndDetachTheLink() {
        Link link = new Link(null, 1, 1L);
        var element = AriadneCoroutineBridge.asElement(link);

        AriadneContext.Scope scope = element.updateThreadContext(element);
        assertThat(AriadneContext.current()).isSameAs(link);

        element.restoreThreadContext(element, scope);
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void restoreBringsBackThePreviousLinkNotJustNull() {
        Link previous = new Link(null, 1, 1L);
        Link coroutineLink = new Link(previous, 2, 1L);
        AriadneContext.set(previous);

        var element = AriadneCoroutineBridge.asElement(coroutineLink);
        AriadneContext.Scope scope = element.updateThreadContext(element);
        assertThat(AriadneContext.current()).isSameAs(coroutineLink);
        element.restoreThreadContext(element, scope);

        assertThat(AriadneContext.current()).isSameAs(previous);
    }

    @Test
    void asElementRecordsAHopRootedAtTheCurrentLink() {
        Link root = new Link(null, 1, 1L);
        AriadneContext.set(root);

        Link link = AriadneCoroutineBridge.linkOf(AriadneCoroutineBridge.asElement());

        assertThat(link.parent).isSameAs(root);
        assertThat(link.depth).isEqualTo(2);
    }

    @Test
    void linkOfReturnsNullWithoutAnElement() {
        assertThat(AriadneCoroutineBridge.linkOf(kotlin.coroutines.EmptyCoroutineContext.INSTANCE)).isNull();
    }

    @Test
    void runBlockingInstallsTheLinkForTheCoroutineBody() throws Exception {
        Link link = new Link(null, 7, 1L);
        CoroutineContext context = AriadneCoroutineBridge.asElement(link);

        Link seen = BuildersKt.runBlocking(context, (scope, continuation) -> AriadneContext.current());

        assertThat(seen).isSameAs(link);
        assertThat(AriadneContext.current()).isNull();
    }

    @Test
    void linkIsAvailableOnDispatcherThreadsAndDoesNotLeakToUnrelatedCoroutines() throws Exception {
        Link link = new Link(null, 9, 1L);
        CoroutineContext withElement = Dispatchers.getDefault().plus(AriadneCoroutineBridge.asElement(link));
        AtomicReference<Thread> bodyThread = new AtomicReference<>();

        Link seen = BuildersKt.runBlocking(withElement, (scope, continuation) -> {
            bodyThread.set(Thread.currentThread());
            return AriadneContext.current();
        });
        assertThat(seen).isSameAs(link);
        assertThat(bodyThread.get()).isNotSameAs(Thread.currentThread());

        // Same dispatcher, no element: must observe NO link (nothing leaked from the previous run)
        for (int i = 0; i < 50; i++) {
            Link leaked = BuildersKt.runBlocking(Dispatchers.getDefault(),
                    (scope, continuation) -> AriadneContext.current());
            assertThat(leaked).isNull();
        }
    }

    @Test
    void enrichAttachesTheAsyncPathFromTheCoroutineContext() {
        Link link = new Link(new Link(null, 1, 1L), 2, 1L);
        CoroutineContext context = AriadneCoroutineBridge.asElement(link);
        RuntimeException failure = new RuntimeException("boom");

        AriadneCoroutineBridge.enrich(context, failure);

        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0].getMessage()).startsWith("Asynchronous execution path (2 hops)");
        // idempotent
        AriadneCoroutineBridge.enrich(context, failure);
        assertThat(failure.getSuppressed()).hasSize(1);
        // no element -> untouched, no exception
        RuntimeException other = new RuntimeException("x");
        AriadneCoroutineBridge.enrich(kotlin.coroutines.EmptyCoroutineContext.INSTANCE, other);
        assertThat(other.getSuppressed()).isEmpty();
        assertThat(AriadneReconstructor.buildSyntheticException(link)).isNotNull();
    }
}
