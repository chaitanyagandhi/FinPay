package com.finpay.user.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.user.dto.AddBeneficiaryRequest;
import com.finpay.user.dto.BeneficiaryResponse;
import com.finpay.user.entity.Beneficiary;
import com.finpay.user.entity.UserProfile;
import com.finpay.user.exception.BeneficiaryAlreadySavedException;
import com.finpay.user.exception.BeneficiaryNotFoundException;
import com.finpay.user.exception.CannotAddSelfAsBeneficiaryException;
import com.finpay.user.repository.BeneficiaryRepository;
import com.finpay.user.repository.UserProfileRepository;

/**
 * Managing the people a user can pay.
 *
 * <p>Every operation is scoped to the authenticated owner, and no endpoint can name a different
 * one. That is what removes the need for an authorization rule here: there is no request that
 * could address somebody else's payee list for a rule to have to refuse.
 */
@Service
public class BeneficiaryService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryService.class);

    private final BeneficiaryRepository beneficiaries;
    private final UserProfileRepository profiles;

    public BeneficiaryService(BeneficiaryRepository beneficiaries, UserProfileRepository profiles) {
        this.beneficiaries = beneficiaries;
        this.profiles = profiles;
    }

    /** An owner's saved payees, newest first. */
    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> list(UUID ownerId) {
        return beneficiaries.findByOwner(ownerId).stream()
                // The query fetched each payee's profile, so this loads nothing further.
                .map(beneficiary -> BeneficiaryResponse.of(beneficiary, beneficiary.getBeneficiaryProfile()))
                .toList();
    }

    /**
     * Saves a payee.
     *
     * @throws CannotAddSelfAsBeneficiaryException if the owner named themselves
     * @throws BeneficiaryNotFoundException if the payee has no profile and so cannot be shown
     * @throws BeneficiaryAlreadySavedException if this owner already saved them
     */
    @Transactional
    public BeneficiaryResponse add(UUID ownerId, AddBeneficiaryRequest request) {
        UUID payeeId = request.beneficiaryUserId();

        if (ownerId.equals(payeeId)) {
            throw new CannotAddSelfAsBeneficiaryException();
        }

        // A payee must be identifiable: confirming a payment means seeing who is about to be paid,
        // and an account with no profile has no name to show. This is the same rule the foreign
        // key enforces - checked here so the caller gets an answer rather than a constraint error.
        UserProfile payee = profiles.findById(payeeId).orElseThrow(() -> {
            log.info("Owner {} tried to save a payee with no profile", ownerId);
            return new BeneficiaryNotFoundException();
        });

        if (beneficiaries.existsByOwnerUserIdAndBeneficiaryUserId(ownerId, payeeId)) {
            throw new BeneficiaryAlreadySavedException();
        }

        try {
            Beneficiary saved = beneficiaries.saveAndFlush(Beneficiary.save(ownerId, payeeId, request.nickname()));
            log.info("Owner {} saved payee {}", ownerId, payeeId);

            // The profile is the one already loaded above; no second query, and no reliance on an
            // association the saved instance does not have.
            return BeneficiaryResponse.of(saved, payee);
        } catch (DataIntegrityViolationException e) {
            // The check above can lose a race with a concurrent save of the same payee. Catching
            // the unique constraint means the loser sees the same 409 as anybody else rather than
            // a 500, and the database stays the thing that actually decides.
            log.info("Owner {} lost a race saving payee {}", ownerId, payeeId);
            throw new BeneficiaryAlreadySavedException();
        }
    }

    /**
     * Removes one of the owner's payees.
     *
     * <p>The owner is part of the delete statement rather than checked first, so an entry
     * belonging to somebody else simply does not match. The caller cannot tell that from an entry
     * that never existed, which is the point.
     *
     * @throws BeneficiaryNotFoundException if no such entry belongs to this owner
     */
    @Transactional
    public void remove(UUID ownerId, UUID beneficiaryId) {
        if (beneficiaries.deleteOwnedBy(beneficiaryId, ownerId) == 0) {
            throw new BeneficiaryNotFoundException();
        }

        log.info("Owner {} removed payee entry {}", ownerId, beneficiaryId);
    }
}
