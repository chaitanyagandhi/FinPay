package com.finpay.user.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.finpay.user.entity.Beneficiary;

/** Data access for {@link Beneficiary}. Every query is scoped to one owner. */
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    /**
     * One owner's payees, newest first, with each payee's profile in the same query.
     *
     * <p>{@code join fetch} rather than a lazy association read per row: this is the list a user
     * opens most often, and an N+1 here is one query per saved payee.
     *
     * <p>The ordering matches {@code idx_beneficiaries_owner}, which is on
     * {@code (owner_user_id, created_at DESC)}.
     */
    @Query(
            """
            select b from Beneficiary b
              join fetch b.beneficiaryProfile
             where b.ownerUserId = :ownerId
             order by b.createdAt desc
            """)
    List<Beneficiary> findByOwner(@Param("ownerId") UUID ownerId);

    boolean existsByOwnerUserIdAndBeneficiaryUserId(UUID ownerUserId, UUID beneficiaryUserId);

    /**
     * Removes one of an owner's payees.
     *
     * <p>The owner is part of the statement rather than checked beforehand. A delete by id alone,
     * with the ownership check in Java, is one forgotten condition away from letting anybody
     * remove anybody's payee - and the forgotten version still passes every test that only ever
     * deletes its own.
     *
     * @return 1 when a row belonging to this owner was removed, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Beneficiary b where b.id = :id and b.ownerUserId = :ownerId")
    int deleteOwnedBy(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
