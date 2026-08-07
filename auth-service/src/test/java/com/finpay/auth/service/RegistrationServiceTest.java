package com.finpay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.auth.dto.RegistrationResponse;
import com.finpay.auth.entity.Credential;
import com.finpay.auth.entity.Role;
import com.finpay.auth.entity.User;
import com.finpay.auth.entity.UserStatus;
import com.finpay.auth.exception.AuthErrorCode;
import com.finpay.auth.exception.EmailAlreadyRegisteredException;
import com.finpay.auth.repository.CredentialRepository;
import com.finpay.auth.repository.UserRepository;

/**
 * Covers the decisions registration makes, without a database.
 *
 * <p>A real {@link BCryptPasswordEncoder} is used rather than a mock: the single most important
 * property of this service is that a plaintext password never reaches storage, and a stubbed
 * encoder returning a canned string would let that regress unnoticed.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Mock
    private UserRepository users;

    @Mock
    private CredentialRepository credentials;

    // Strength 4 is the BCrypt minimum. Correctness does not depend on the cost factor, and the
    // production value would add roughly a quarter of a second to every case here.
    @org.mockito.Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @InjectMocks
    private RegistrationService service;

    @Test
    @DisplayName("stores a hash, never the password itself")
    void storesAHashRatherThanThePassword() {
        when(users.existsByEmail(anyString())).thenReturn(false);

        service.register(new RegistrationRequest("ada@finpay.test", PASSWORD));

        ArgumentCaptor<Credential> saved = ArgumentCaptor.forClass(Credential.class);
        verify(credentials).saveAndFlush(saved.capture());

        String stored = saved.getValue().getPasswordHash();
        assertThat(stored).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored))
                .as("the stored hash must still verify the original password")
                .isTrue();
        assertThat(saved.getValue().getAlgorithm()).isEqualTo("BCRYPT");
    }

    @Test
    @DisplayName("gives two identical passwords different hashes")
    void saltsEachPasswordSeparately() {
        when(users.existsByEmail(anyString())).thenReturn(false);

        service.register(new RegistrationRequest("ada@finpay.test", PASSWORD));
        service.register(new RegistrationRequest("grace@finpay.test", PASSWORD));

        ArgumentCaptor<Credential> saved = ArgumentCaptor.forClass(Credential.class);
        verify(credentials, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());

        // Without a per-password salt, one precomputed table would break every account that
        // shares a password.
        assertThat(saved.getAllValues().get(0).getPasswordHash())
                .isNotEqualTo(saved.getAllValues().get(1).getPasswordHash());
    }

    @ParameterizedTest(name = "{0} is stored as ada@finpay.test")
    @DisplayName("normalises the email before storing it")
    @ValueSource(strings = {"ada@finpay.test", "Ada@finpay.test", "ADA@FINPAY.TEST", "  ada@finpay.test  "})
    void normalisesEmail(String submitted) {
        when(users.existsByEmail(anyString())).thenReturn(false);

        RegistrationResponse response = service.register(new RegistrationRequest(submitted, PASSWORD));

        assertThat(response.email()).isEqualTo("ada@finpay.test");
        verify(users).existsByEmail("ada@finpay.test");
    }

    @Test
    @DisplayName("creates the account pending verification, with the USER role")
    void createsPendingUserWithUserRole() {
        when(users.existsByEmail(anyString())).thenReturn(false);

        service.register(new RegistrationRequest("ada@finpay.test", PASSWORD));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());

        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(saved.getValue().isEmailVerified()).isFalse();
        assertThat(saved.getValue().getRoles()).containsExactly(Role.USER);
        assertThat(saved.getValue().getId()).isNotNull();
    }

    @Test
    @DisplayName("rejects an address that already has an account, without writing anything")
    void rejectsDuplicateEmail() {
        when(users.existsByEmail("ada@finpay.test")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegistrationRequest("Ada@finpay.test", PASSWORD)))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .extracting(e -> ((EmailAlreadyRegisteredException) e).errorCode())
                .isEqualTo(AuthErrorCode.EMAIL_ALREADY_REGISTERED);

        verify(users, never()).saveAndFlush(any());
        verify(credentials, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("turns a losing concurrent insert into the same conflict")
    void mapsConstraintViolationToConflict() {
        // Both requests pass the existence check; the database decides the winner. The loser
        // must see the same answer it would have seen a millisecond earlier.
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> service.register(new RegistrationRequest("ada@finpay.test", PASSWORD)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    @DisplayName("never repeats the address back in the failure message")
    void failureMessageDoesNotEchoTheAddress() {
        when(users.existsByEmail(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegistrationRequest("ada@finpay.test", PASSWORD)))
                // Confirming which addresses are registered makes this endpoint an account
                // enumeration oracle.
                .hasMessageNotContaining("ada@finpay.test");
    }
}
