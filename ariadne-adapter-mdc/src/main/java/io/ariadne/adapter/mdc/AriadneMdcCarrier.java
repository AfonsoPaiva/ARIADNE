package io.ariadne.adapter.mdc;

import java.util.Map;
import java.util.Objects;

import org.slf4j.MDC;

import io.ariadne.core.AriadneConfig;
import io.ariadne.core.ContextCarrier;
import io.ariadne.core.Link;

/**
 * ContextCarrier decorator that captures SLF4J {@link MDC} snapshots upon {@link #spawn(int)}
 * and transparently binds/restores them during {@link #attach(Link)}.
 */
public final class AriadneMdcCarrier implements ContextCarrier {

    private final ContextCarrier delegate;

    public AriadneMdcCarrier(ContextCarrier delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate carrier must not be null");
    }

    public ContextCarrier getDelegate() {
        return delegate;
    }

    @Override
    public Link current() {
        return delegate.current();
    }

    @Override
    public void set(Link link) {
        delegate.set(link);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public Link spawn(int siteId) {
        return spawn(siteId, null);
    }

    @Override
    public Link spawn(int siteId, Object attachment) {
        Object payload = attachment;
        if (payload == null && AriadneConfig.isMdcPropagationEnabled()) {
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            if (mdcContext != null && !mdcContext.isEmpty()) {
                payload = mdcContext;
            }
        }
        return delegate.spawn(siteId, payload);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Scope attach(Link link) {
        Scope delegateScope = delegate.attach(link);

        if (!AriadneConfig.isMdcPropagationEnabled() || link == null || !(link.attachment instanceof Map<?, ?>)) {
            return delegateScope;
        }

        Map<String, String> targetMdc = (Map<String, String>) link.attachment;
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();

        MDC.setContextMap(targetMdc);

        return () -> {
            try {
                if (previousMdc == null || previousMdc.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            } finally {
                delegateScope.close();
            }
        };
    }
}
