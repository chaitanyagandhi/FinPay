package com.finpay.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Normalisation that has to happen before validation runs.
 *
 * <p>Bean validation is applied to the deserialised record, so anything the compact constructor
 * does not fix is already too late: a padded phone number fails the E.164 pattern before any
 * service code could trim it, and the caller gets a 400 for a value that was fine.
 */
class UpdateProfileRequestTest {

    @Test
    @DisplayName("trims every text field")
    void trimsTextFields() {
        UpdateProfileRequest request =
                new UpdateProfileRequest("  ada  ", " Ada ", " Lovelace ", " +441632960961 ", " gb ", " UTC ", " a/b ");

        assertThat(request.displayName()).isEqualTo("ada");
        assertThat(request.firstName()).isEqualTo("Ada");
        assertThat(request.lastName()).isEqualTo("Lovelace");
        assertThat(request.phoneNumber()).isEqualTo("+441632960961");
        assertThat(request.timezone()).isEqualTo("UTC");
        assertThat(request.avatarUrl()).isEqualTo("a/b");
    }

    @ParameterizedTest(name = "{0} becomes {1}")
    @DisplayName("upper-cases the country code, so one country has one spelling")
    @CsvSource({"gb,GB", "Gb,GB", "GB,GB", " us ,US"})
    void normalisesCountryCode(String supplied, String expected) {
        assertThat(new UpdateProfileRequest(null, null, null, null, supplied, null, null).countryCode())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("leaves an absent field absent rather than turning it into an empty string")
    void keepsNullsNull() {
        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null, null, null, null, null);

        // Null means "leave this alone". An empty string would mean "set it to nothing", which is
        // how a client that sends one field wipes the rest.
        assertThat(request.displayName()).isNull();
        assertThat(request.phoneNumber()).isNull();
        assertThat(request.countryCode()).isNull();
        assertThat(request.timezone()).isNull();
    }
}
