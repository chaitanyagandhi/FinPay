package com.finpay.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.finpay.user.entity.UserProfile;

/** Data access for {@link UserProfile}. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByPhoneNumber(String phoneNumber);

    /**
     * Finds people whose display name starts with the term.
     *
     * <p>A prefix match, not a substring one. "contains" would let a two-character fragment match
     * a large share of the directory, which turns finding a payee into enumerating the user base -
     * and it cannot use an index, so the cost of that enumeration falls on the database.
     *
     * <p>Lower-cased on both sides to match {@code idx_user_profiles_display_name}, which is built
     * on {@code lower(display_name)}; comparing the raw column would silently skip the index.
     *
     * @param excludedUserId the caller, who has no reason to find themselves in a payee list
     */
    @Query(
            """
            select p from UserProfile p
             where p.displayName is not null
               and lower(p.displayName) like concat(lower(:term), '%')
               and p.userId <> :excludedUserId
             order by lower(p.displayName)
            """)
    List<UserProfile> searchByDisplayNamePrefix(
            @Param("term") String term, @Param("excludedUserId") UUID excludedUserId, Limit limit);
}
