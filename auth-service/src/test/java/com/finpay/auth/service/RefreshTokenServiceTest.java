package com.finpay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hashing rule that decides what a database leak is worth.
 *
 * <p>Everything else about rotation needs a database to mean anything and is covered by {@code
 * RefreshTokenRotationIT}. What can be pinned down here is the property the whole scheme rests on:
 * the stored value must be derived from the token and must not be the token.
 */
class RefreshTokenServiceTest {

    @Test
    @DisplayName("hashes to a value that is not the token")
    void neverStoresTheTokenItself() {
        String token = "a-refresh-token-value";

        assertThat(RefreshTokenService.hash(token)).isNotEqualTo(token).doesNotContain(token);
    }

    @Test
    @DisplayName("produces a stable 64-character hex digest, which is what makes lookup by hash possible")
    void hashesDeterministically() {
        String token = "a-refresh-token-value";

        String first = RefreshTokenService.hash(token);
        String second = RefreshTokenService.hash(token);

        // Determinism is the deliberate difference from password hashing: a per-row salt would
        // make "find the row for this token" a full scan instead of one unique-index hit.
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("agrees with a plain SHA-256, so any consumer can reproduce it")
    void isPlainSha256() throws Exception {
        String token = "a-refresh-token-value";

        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));

        assertThat(RefreshTokenService.hash(token)).isEqualTo(expected);
    }

    @Test
    @DisplayName("different tokens hash differently")
    void distinguishesTokens() {
        // Collisions here would let one session's token unlock another's row.
        var hashes = IntStream.range(0, 500)
                .mapToObj(i -> RefreshTokenService.hash("token-" + i))
                .collect(Collectors.toSet());

        assertThat(hashes).hasSize(500);
    }
}
