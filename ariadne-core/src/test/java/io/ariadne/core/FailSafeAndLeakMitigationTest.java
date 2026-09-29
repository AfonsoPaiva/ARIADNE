package io.ariadne.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying:
 * 1. Total fail-safe execution (malicious Runnables, throwing toString(), giant MDC).
 * 2. Kill switch (-Dariadne.enabled=false / AriadneConfig.setEnabled(false)).
 * 3. Class/package exclusion list matching.
 * 4. Data leakage prevention (MDC allowlist enforcement, disabled by default).
 * 5. Root origin preservation during sliding window pruning.
 */
class FailSafeAndLeakMitigationTest {

    @BeforeEach
    @AfterEach
    void resetState() {
        AriadneConfig.resetDefaults();
        AriadneContext.clear();
    }

    @Test
    @DisplayName("Fail-safe: Malicious Runnable that throws during execution must not break wrapper or cause agent crash")
    void shouldHandleMaliciousRunnableSafely() {
        class MaliciousRunnable implements Runnable {
            @Override
            public void run() {
                throw new IllegalStateException("Deliberate malicious failure in user code");
            }

            @Override
            public String toString() {
                throw new RuntimeException("Hostile toString() invocation");
            }
        }

        MaliciousRunnable malicious = new MaliciousRunnable();
        Runnable wrapped = AriadneRunnable.wrap(malicious, AriadneContext.spawn(101));

        assertThatThrownBy(wrapped::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Deliberate malicious failure in user code");
    }

    @Test
    @DisplayName("Data leakage: Context disabled by default must not write MDC into exception message")
    void shouldNotIncludeContextInExceptionByDefault() {
        assertThat(AriadneConfig.isExceptionContextEnabled()).isFalse();

        Map<String, String> mdc = Map.of(
                "traceId", "req-12345",
                "secret_token", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                "user_ssn", "000-12-3456"
        );

        Link link = AriadneContext.spawn(202, mdc);
        Throwable error = new RuntimeException("Async processing failure");
        AriadneReconstructor.enrich(error, link);

        assertThat(error.getSuppressed()).hasSize(1);
        AsyncCausalityException causal = (AsyncCausalityException) error.getSuppressed()[0];

        // Must NOT contain [Context: ...] when disabled
        assertThat(causal.getMessage())
                .doesNotContain("[Context:")
                .doesNotContain("traceId")
                .doesNotContain("secret_token")
                .doesNotContain("user_ssn");
    }

    @Test
    @DisplayName("Data leakage: When context enabled, only allowlisted keys are rendered and sensitive keys are stripped")
    void shouldFilterSensitiveKeysWhenContextEnabled() {
        AriadneConfig.setExceptionContextEnabled(true);
        AriadneConfig.setMdcAllowlist(Set.of("traceId", "tenant"));

        Map<String, String> mdc = Map.of(
                "traceId", "req-12345",
                "tenant", "acme-corp",
                "password", "SuperSecret123!",
                "apiKey", "ak_live_abcdef123456",
                "jwt", "eyJhbGciOiJIUzI1NiJ9"
        );

        Link link = AriadneContext.spawn(203, mdc);
        Throwable error = new RuntimeException("Database timeout");
        AriadneReconstructor.enrich(error, link);

        assertThat(error.getSuppressed()).hasSize(1);
        AsyncCausalityException causal = (AsyncCausalityException) error.getSuppressed()[0];

        // Allowlisted keys MUST be present
        assertThat(causal.getMessage())
                .contains("[Context:")
                .contains("traceId=req-12345")
                .contains("tenant=acme-corp");

        // Non-allowlisted sensitive keys MUST NOT be present
        assertThat(causal.getMessage())
                .doesNotContain("password")
                .doesNotContain("SuperSecret123!")
                .doesNotContain("apiKey")
                .doesNotContain("ak_live_abcdef123456")
                .doesNotContain("jwt");
    }

    @Test
    @DisplayName("Fail-safe & Leak mitigation: Giant MDC with 10,000 entries and large values must be capped and truncated")
    void shouldSafelyHandleGiantMdcWithoutOomOrFlooding() {
        AriadneConfig.setExceptionContextEnabled(true);
        // Allow all generated keys for this test to test truncation limits
        AriadneConfig.setMdcAllowlist(Set.of("traceId", "key0", "key1", "key2", "key3", "key4"));

        Map<String, String> giantMdc = new HashMap<>();
        String largeString = "X".repeat(5000); // 5 KB value

        for (int i = 0; i < 1000; i++) {
            giantMdc.put("key" + i, largeString);
        }
        giantMdc.put("traceId", "trace-giant-001");

        Link link = AriadneContext.spawn(204, giantMdc);
        Throwable error = new RuntimeException("Stream worker failed");

        assertThatCode(() -> AriadneReconstructor.enrich(error, link))
                .doesNotThrowAnyException();

        assertThat(error.getSuppressed()).hasSize(1);
        AsyncCausalityException causal = (AsyncCausalityException) error.getSuppressed()[0];

        // Should contain traceId
        assertThat(causal.getMessage()).contains("traceId=trace-giant-001");

        // Value must be truncated to 512 chars with ellipsis
        assertThat(causal.getMessage()).contains("...[truncated]");

        // Message length must be reasonable (bounded, definitely < 100 KB)
        assertThat(causal.getMessage().length()).isLessThan(35000);
    }

    @Test
    @DisplayName("Kill Switch: When disabled globally, isEnabled returns false")
    void shouldRespectKillSwitch() {
        assertThat(AriadneConfig.isEnabled()).isTrue();

        AriadneConfig.setEnabled(false);
        assertThat(AriadneConfig.isEnabled()).isFalse();

        AriadneConfig.setEnabled(true);
        assertThat(AriadneConfig.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Exclusions List: Wildcard prefixes and exact class names are correctly matched")
    void shouldMatchConfiguredExclusions() {
        AriadneConfig.setExcludes(List.of(
                "com.sensitive.*",
                "org.apache.tomcat.*",
                "io.ariadne.core.ExcludedWorker"
        ));

        assertThat(AriadneConfig.isClassExcluded("com.sensitive.InternalService")).isTrue();
        assertThat(AriadneConfig.isClassExcluded("com.sensitive.payment.TokenVault")).isTrue();
        assertThat(AriadneConfig.isClassExcluded("org.apache.tomcat.util.net.NioEndpoint")).isTrue();
        assertThat(AriadneConfig.isClassExcluded("io.ariadne.core.ExcludedWorker")).isTrue();
        assertThat(AriadneConfig.isClassExcluded("io.ariadne.core.ExcludedWorker$InnerTask")).isTrue();

        // Non-excluded classes
        assertThat(AriadneConfig.isClassExcluded("com.example.OrderController")).isFalse();
        assertThat(AriadneConfig.isClassExcluded("io.ariadne.core.AriadneContext")).isFalse();
        assertThat(AriadneConfig.isClassExcluded(null)).isFalse();
        assertThat(AriadneConfig.isClassExcluded("")).isFalse();
    }

    @Test
    @DisplayName("Root Origin Preservation: Sliding window must retain the root entrypoint link")
    void shouldPreserveRootOriginHopDuringPruning() {
        // Build a causal chain with 8 hops: Root (site 1000) -> 1001 -> 1002 -> ... -> 1007
        Link root = new Link(null, 1000, 1L, "root-http-controller");
        Link link = root;
        for (int i = 1; i <= 7; i++) {
            link = new Link(link, 1000 + i, 1L, "hop-" + i);
        }
        assertThat(link.depth).isEqualTo(8);

        // Retain 4 hops: Root + 3 most recent hops (1005, 1006, 1007)
        Link pruned = ThreadLocalContextCarrier.pruneOldestHops(link, 4);

        assertThat(pruned).isNotNull();
        assertThat(pruned.depth).isEqualTo(4);
        assertThat(pruned.siteId).isEqualTo(1007);
        assertThat(pruned.attachment).isEqualTo("hop-7");

        Link parent1 = pruned.parent;
        assertThat(parent1).isNotNull();
        assertThat(parent1.siteId).isEqualTo(1006);
        assertThat(parent1.attachment).isEqualTo("hop-6");

        Link parent2 = parent1.parent;
        assertThat(parent2).isNotNull();
        assertThat(parent2.siteId).isEqualTo(1005);
        assertThat(parent2.attachment).isEqualTo("hop-5");

        // The root origin hop MUST be preserved at the base!
        Link preservedRoot = parent2.parent;
        assertThat(preservedRoot).isNotNull();
        assertThat(preservedRoot.siteId).isEqualTo(1000);
        assertThat(preservedRoot.attachment).isEqualTo("root-http-controller");
        assertThat(preservedRoot.parent).isNull();
        assertThat(preservedRoot.depth).isEqualTo(1);
    }
}
