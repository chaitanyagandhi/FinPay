package com.finpay.user.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.user.dto.UpdateProfileRequest;
import com.finpay.user.dto.UserProfileResponse;
import com.finpay.user.entity.UserProfile;
import com.finpay.user.exception.PhoneNumberAlreadyUsedException;
import com.finpay.user.exception.UnknownTimezoneException;
import com.finpay.user.repository.UserProfileRepository;

/**
 * Reading and editing the caller's own profile.
 *
 * <p>Every operation is scoped to the authenticated user id, which comes from the gateway and
 * never from the request body. There is deliberately no way to name a different user: an endpoint
 * that accepted one would need an authorization rule to make it safe, and the rule would be the
 * only thing standing between any account and every other.
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileRepository profiles;

    public UserProfileService(UserProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * The caller's profile, or an empty one if they have never filled anything in.
     *
     * <p>No row is created here. Writing on a GET would make a safe method have side effects, and
     * would fill the table with blank rows for accounts that never edit anything.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse profileOf(UUID userId) {
        return profiles.findById(userId)
                .map(UserProfileResponse::of)
                .orElseGet(() -> UserProfileResponse.empty(userId));
    }

    /**
     * Applies a partial update, creating the profile on first use.
     *
     * @throws PhoneNumberAlreadyUsedException if the number belongs to another account
     * @throws UnknownTimezoneException if the zone is not one the JVM recognises
     */
    @Transactional
    public UserProfileResponse update(UUID userId, UpdateProfileRequest request) {
        if (request.timezone() != null) {
            requireKnownTimezone(request.timezone());
        }
        if (request.phoneNumber() != null) {
            requirePhoneNumberIsFree(userId, request.phoneNumber());
        }

        UserProfile profile = profiles.findById(userId).orElseGet(() -> UserProfile.forUser(userId));

        profile.apply(
                request.displayName(),
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                request.countryCode(),
                request.timezone(),
                request.avatarUrl());

        try {
            UserProfile saved = profiles.saveAndFlush(profile);
            log.info("Updated profile for user {}", userId);
            return UserProfileResponse.of(saved);
        } catch (DataIntegrityViolationException e) {
            // The check above can lose a race with a concurrent update claiming the same number.
            // Catching the constraint as well means the loser sees the same 409 as anyone else
            // rather than a 500, and the database stays the thing that actually decides.
            log.info("Profile update for user {} lost a race on the phone number", userId);
            throw new PhoneNumberAlreadyUsedException();
        }
    }

    /** A number already on somebody else's profile cannot be taken; keeping your own is fine. */
    private void requirePhoneNumberIsFree(UUID userId, String phoneNumber) {
        Optional<UserProfile> holder = profiles.findByPhoneNumber(phoneNumber);

        if (holder.isPresent() && !holder.get().getUserId().equals(userId)) {
            throw new PhoneNumberAlreadyUsedException();
        }
    }

    private void requireKnownTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new UnknownTimezoneException(timezone);
        }
    }
}
