package com.finpay.user.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.finpay.platform.web.identity.CallerIdentity;
import com.finpay.user.dto.AddBeneficiaryRequest;
import com.finpay.user.dto.BeneficiaryResponse;
import com.finpay.user.service.BeneficiaryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The people a user has saved in order to pay them.
 *
 * <p>Holds no logic: it validates the request shape, delegates, and maps the result onto HTTP.
 *
 * <p>The owner is always the caller, taken from the identity the gateway supplies. No operation
 * here can name a different owner, which is why none of them carries an authorization check -
 * there is no request that could reach somebody else's payee list.
 */
@RestController
@RequestMapping("/api/v1/beneficiaries")
@Tag(name = "Beneficiaries", description = "Saved payees")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaries;

    public BeneficiaryController(BeneficiaryService beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @GetMapping
    @Operation(
            summary = "List saved payees",
            description =
                    """
                    Returns the caller's saved payees, newest first. Each payee is described by an id, \
                    a display name and an avatar - the same shape the directory returns, so this cannot \
                    be used to learn more about someone than searching for them would.""")
    @ApiResponse(responseCode = "200", description = "The caller's payees, possibly none.")
    public List<BeneficiaryResponse> list(CallerIdentity caller) {
        return beneficiaries.list(caller.userId());
    }

    @PostMapping
    @Operation(
            summary = "Save a payee",
            description =
                    """
                    Saves another user as a payee, named by the user id the directory returns. The payee \
                    must have a profile: confirming a payment means being able to show who is about to \
                    be paid. Saving yourself is refused, and saving the same person twice is a conflict \
                    rather than a silent success.""")
    @ApiResponse(responseCode = "201", description = "The payee was saved.")
    @ApiResponse(responseCode = "400", description = "The payee is the caller.")
    @ApiResponse(responseCode = "404", description = "No such payee.")
    @ApiResponse(responseCode = "409", description = "That payee is already saved.")
    public ResponseEntity<BeneficiaryResponse> add(
            CallerIdentity caller, @Valid @RequestBody AddBeneficiaryRequest request) {

        BeneficiaryResponse saved = beneficiaries.add(caller.userId(), request);

        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/beneficiaries/{id}")
                        .build(saved.id()))
                .body(saved);
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Remove a saved payee",
            description =
                    """
                    Removes one of the caller's payees. An entry that belongs to somebody else answers \
                    exactly as one that never existed, so this cannot be used to discover other \
                    people's payees.""")
    @ApiResponse(responseCode = "204", description = "The payee was removed.")
    @ApiResponse(responseCode = "404", description = "No such payee belongs to the caller.")
    public ResponseEntity<Void> remove(CallerIdentity caller, @PathVariable("id") UUID id) {
        beneficiaries.remove(caller.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
