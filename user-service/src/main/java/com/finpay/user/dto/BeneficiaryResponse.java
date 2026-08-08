package com.finpay.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.finpay.user.entity.Beneficiary;
import com.finpay.user.entity.UserProfile;

/**
 * A saved payee.
 *
 * <p>The payee is carried as a {@link UserSummary} - an id, a display name and an avatar, and
 * nothing else. A payee list is a list of other people, so it reveals exactly what the directory
 * reveals and no more; returning phone numbers here would work around the limits the search
 * endpoint exists to impose.
 *
 * @param id identifies the saved entry, not the person; this is what DELETE takes
 * @param nickname what the owner calls them, null if they never said
 */
public record BeneficiaryResponse(UUID id, String nickname, UserSummary user, Instant createdAt) {

    /**
     * Builds the response from an entry and the payee's profile.
     *
     * <p>The profile is passed in rather than read off the entry, because the entry only has one
     * when it was loaded by a query that fetched it. An entity that was just saved is already in
     * the persistence context with the association unset, and re-reading it returns that same
     * instance rather than a freshly joined one - so taking it from the entry works when listing
     * and quietly yields null right after a save.
     */
    public static BeneficiaryResponse of(Beneficiary beneficiary, UserProfile payee) {
        return new BeneficiaryResponse(
                beneficiary.getId(), beneficiary.getNickname(), UserSummary.of(payee), beneficiary.getCreatedAt());
    }
}
