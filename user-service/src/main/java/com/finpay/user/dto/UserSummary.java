package com.finpay.user.dto;

import java.util.UUID;

import com.finpay.user.entity.UserProfile;

/**
 * What one user is allowed to learn about another.
 *
 * <p>Deliberately tiny. A payee search exists so somebody can be paid, and an id plus a display
 * name is everything that requires - a result carrying phone numbers or real names would turn the
 * directory into a harvesting tool for anyone with an account.
 *
 * @param userId what a payment is addressed to
 * @param displayName what the payer sees when confirming
 */
public record UserSummary(UUID userId, String displayName, String avatarUrl) {

    public static UserSummary of(UserProfile profile) {
        return new UserSummary(profile.getUserId(), profile.getDisplayName(), profile.getAvatarUrl());
    }
}
