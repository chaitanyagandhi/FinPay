package com.finpay.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers which client-supplied request ids are trusted and which are replaced. */
class RequestCorrelationTest {

    @ParameterizedTest(name = "accepts {0}")
    @DisplayName("reuses a well-formed inbound id so one identifier spans every service")
    @ValueSource(
            strings = {
                "0d5a1f6c-1c2b-4b1e-9f6a-1a2b3c4d5e6f",
                "req-12345",
                "REQ_12345",
                "a",
                "0123456789012345678901234567890123456789012345678901234567890123"
            })
    void reusesAcceptableInboundIds(String inbound) {
        assertThat(RequestCorrelation.resolve(inbound)).isEqualTo(inbound);
    }

    @ParameterizedTest(name = "replaces {0}")
    @DisplayName("replaces an id that could forge or break log entries")
    @ValueSource(
            strings = {
                // Newlines would let a caller inject fabricated lines into the log stream.
                "req\n12345",
                "req\r\nlevel=ERROR",
                // Control and quoting characters corrupt structured output.
                "req\t123",
                "req\"123",
                "{\"injected\":true}",
                "req 123",
                "../../etc/passwd",
                // Longer than the accepted maximum.
                "01234567890123456789012345678901234567890123456789012345678901234"
            })
    void replacesUnacceptableInboundIds(String inbound) {
        String resolved = RequestCorrelation.resolve(inbound);

        assertThat(resolved).isNotEqualTo(inbound);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("generates an id when the client did not send one")
    void generatesWhenAbsent(String inbound) {
        String resolved = RequestCorrelation.resolve(inbound);

        assertThat(resolved).isNotBlank();
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    @DisplayName("generates a distinct id each time")
    void generatesDistinctIds() {
        assertThat(RequestCorrelation.generate()).isNotEqualTo(RequestCorrelation.generate());
    }
}
