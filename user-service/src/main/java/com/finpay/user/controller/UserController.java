package com.finpay.user.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.finpay.platform.web.identity.CallerIdentity;
import com.finpay.user.dto.UpdateProfileRequest;
import com.finpay.user.dto.UserProfileResponse;
import com.finpay.user.dto.UserSummary;
import com.finpay.user.service.UserProfileService;
import com.finpay.user.service.UserSearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Profile and directory endpoints.
 *
 * <p>Holds no logic: it validates the request shape, delegates, and maps the result onto HTTP.
 *
 * <p>{@link CallerIdentity} is supplied by the shared argument resolver from the headers the
 * gateway writes after it has validated the access token. Every operation here acts on that
 * identity and there is no way to name a different user - which is why none of these methods needs
 * an authorization check of its own.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Profiles and the payee directory")
public class UserController {

    private final UserProfileService profileService;
    private final UserSearchService searchService;

    public UserController(UserProfileService profileService, UserSearchService searchService) {
        this.profileService = profileService;
        this.searchService = searchService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "The signed-in user's profile",
            description =
                    """
                    Returns the caller's own profile. An account that has never filled anything in gets \
                    an empty profile rather than a 404: the account exists, and nothing is written to \
                    the database by a request that only reads. The email address is not included - it \
                    belongs to the authentication service.""")
    @ApiResponse(responseCode = "200", description = "The profile, possibly empty.")
    public UserProfileResponse currentProfile(CallerIdentity caller) {
        return profileService.profileOf(caller.userId());
    }

    @PatchMapping("/me")
    @Operation(
            summary = "Update the signed-in user's profile",
            description =
                    """
                    Applies a partial update and creates the profile on first use. Omitted fields are \
                    left alone, so sending one field never erases the others. The profile updated is \
                    always the caller's; there is no way to name a different user.""")
    @ApiResponse(responseCode = "200", description = "The updated profile.")
    @ApiResponse(responseCode = "409", description = "The phone number is already in use.")
    public UserProfileResponse updateCurrentProfile(
            CallerIdentity caller, @Valid @RequestBody UpdateProfileRequest request) {

        return profileService.update(caller.userId(), request);
    }

    @GetMapping("/search")
    @Operation(
            summary = "Find someone to pay",
            description =
                    """
                    Searches the directory by full phone number in E.164, or by the start of a display \
                    name. Results carry only an id, a display name and an avatar - never a phone number \
                    or a real name. A term shorter than the configured minimum returns nothing, results \
                    are capped and cannot be paged, and no match is an empty list rather than a 404, so \
                    this cannot be used to enumerate accounts.""")
    @ApiResponse(responseCode = "200", description = "Matching users, possibly none.")
    public List<UserSummary> search(CallerIdentity caller, @RequestParam("q") String term) {
        return searchService.search(term, caller.userId());
    }
}
