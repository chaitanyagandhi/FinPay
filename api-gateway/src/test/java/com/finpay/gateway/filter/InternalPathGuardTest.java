package com.finpay.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers the path matching behind the internal-endpoint block, including the paths that must
 * <em>not</em> be blocked.
 */
class InternalPathGuardTest {

    @ParameterizedTest(name = "blocks {0}")
    @DisplayName("blocks service-internal paths")
    @ValueSource(
            strings = {
                "/internal/v1/wallets/123/reserve",
                "/internal/v1/wallets/123/release",
                "/internal/v1/wallets/123/credit",
                "/internal/v1/wallets/123/finalize-debit",
                "/internal",
                "/internal/",
                // Case variations: the block must not be defeated by spelling.
                "/INTERNAL/v1/wallets",
                "/Internal/v1/wallets",
                // Traversal: normalisation resolves this to /internal/v1/wallets.
                "/api/v1/../internal/v1/wallets",
                "/api/v1/wallets/../../../internal/v1/wallets",
                // An internal segment anywhere in the path, not only at the start.
                "/api/v1/internal/wallets"
            })
    void blocksInternalPaths(String path) {
        assertThat(InternalPathGuard.isInternalPath(path)).isTrue();
    }

    @ParameterizedTest(name = "allows {0}")
    @DisplayName("allows public paths, including ones that merely contain the word")
    @ValueSource(
            strings = {
                "/api/v1/auth/login",
                "/api/v1/wallets/me",
                "/api/v1/payments/transfers",
                "/actuator/health",
                "/",
                // "internal" as a prefix of a longer segment is a different word: these are
                // legitimate public paths and blocking them would be a bug.
                "/api/v1/internal-transfers",
                "/api/v1/wallets/internally-managed",
                "/api/v1/internals"
            })
    void allowsPublicPaths(String path) {
        assertThat(InternalPathGuard.isInternalPath(path)).isFalse();
    }
}
