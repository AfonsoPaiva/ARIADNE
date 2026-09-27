package io.ariadne.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkTest {

    @Test
    void shouldCreateRootLinkWithCorrectAttributes() {
        Link root = new Link(null, 101, 42L);

        assertThat(root.parent).isNull();
        assertThat(root.siteId).isEqualTo(101);
        assertThat(root.threadId).isEqualTo(42L);
        assertThat(root.depth()).isEqualTo(1);
    }

    @Test
    void shouldChainLinksAndCalculateDepth() {
        Link root = new Link(null, 1, 10L);
        Link second = new Link(root, 2, 20L);
        Link third = new Link(second, 3, 30L);

        assertThat(third.parent).isSameAs(second);
        assertThat(second.parent).isSameAs(root);
        assertThat(root.parent).isNull();

        assertThat(root.depth()).isEqualTo(1);
        assertThat(second.depth()).isEqualTo(2);
        assertThat(third.depth()).isEqualTo(3);
    }
}
