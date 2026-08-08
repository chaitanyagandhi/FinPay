package com.finpay.user.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A payee to save.
 *
 * <p>The payee is named by user id, which is what the directory returns. There is no way to save
 * somebody by email or phone number here: those belong to the search endpoint, which decides how
 * much it is willing to reveal, and accepting them here would be a second lookup with its own,
 * probably different, rules about what it confirms.
 *
 * <p>There is no owner field. The owner is always the caller.
 *
 * @param beneficiaryUserId the payee, as returned by the directory
 * @param nickname optional; what the owner wants to call them
 */
public record AddBeneficiaryRequest(
        @NotNull(message = "must not be null")
                @Schema(description = "The user id of the payee, as returned by /api/v1/users/search")
                UUID beneficiaryUserId,
        @Size(max = 80, message = "must be at most 80 characters") @Schema(example = "Mum") String nickname) {

    public AddBeneficiaryRequest {
        nickname = nickname == null ? null : nickname.trim();
        // A nickname of spaces is not a nickname, and the database refuses one. Treating it as
        // absent is kinder than a 400 for a field the caller did not really mean to send.
        if (nickname != null && nickname.isEmpty()) {
            nickname = null;
        }
    }
}
