package com.finpay.auth.service;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.auth.dto.RegistrationResponse;
import com.finpay.auth.entity.Credential;
import com.finpay.auth.entity.User;
import com.finpay.auth.exception.EmailAlreadyRegisteredException;
import com.finpay.auth.repository.CredentialRepository;
import com.finpay.auth.repository.UserRepository;

/**
 * Creates accounts.
 *
 * <p>One transaction covers the user, its role and its credential. A user that exists without a
 * password would be an account nobody can ever sign in to and nobody can re-register, so the three
 * writes succeed together or not at all.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /** Recorded on each credential so hashes can be upgraded in place when this changes. */
    static final String ALGORITHM = "BCRYPT";

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(
            UserRepository users, CredentialRepository credentials, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new account.
     *
     * @throws EmailAlreadyRegisteredException if the address already has an account
     */
    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        String email = normalise(request.email());

        // Checked first so the ordinary case returns a clean 409 rather than surfacing a
        // constraint violation. This check is not sufficient on its own - see below.
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = User.register(email);
        String passwordHash = passwordEncoder.encode(request.password());

        try {
            users.saveAndFlush(user);
            credentials.saveAndFlush(Credential.forUser(user, passwordHash, ALGORITHM));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations for the same address both pass the check above and
            // both try to insert. The unique constraint is what actually prevents the duplicate;
            // the loser arrives here and is told the same thing it would have been told a
            // millisecond earlier. Flushing inside the try is what makes the violation surface
            // here rather than at commit, outside this method's reach.
            throw new EmailAlreadyRegisteredException(e);
        }

        // The identifier is safe to log; the address is personal data and the password is not
        // present in this method's output at all.
        log.info("Registered user {}", user.getId());

        return RegistrationResponse.from(user);
    }

    /**
     * Normalises an address for storage and comparison.
     *
     * <p>Lower-cased so that two accounts cannot differ only by case, which would let one person
     * take another's sign-in identifier. The database enforces the same rule, so a value that
     * skipped this method is rejected rather than silently accepted.
     *
     * <p>{@link Locale#ROOT} rather than the default locale: in a Turkish locale
     * {@code "I".toLowerCase()} produces a dotless i, which would make normalisation depend on
     * where the server happens to be running.
     */
    static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
