package com.finpay.user.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.user.config.UserSearchProperties;
import com.finpay.user.dto.UserSummary;
import com.finpay.user.repository.UserProfileRepository;

/**
 * Finding someone to pay.
 *
 * <p>This endpoint is a directory of real people, so the whole design is about giving a payer
 * enough to identify the person they already have in mind while giving anyone else as little as
 * possible.
 *
 * <ul>
 *   <li><strong>Exact match on a phone number, prefix match on a name.</strong> A substring match
 *       would let a two-character fragment return a large share of the directory.
 *   <li><strong>A minimum term length.</strong> Without it, "a" returns thousands of people.
 *   <li><strong>A hard result cap.</strong> No paging, so there is no way to walk the whole set.
 *   <li><strong>A tiny result shape.</strong> An id and a display name, never a phone number or a
 *       real name - a caller must not learn more by searching than the person chose to publish.
 *   <li><strong>An empty list, never a 404.</strong> "No such user" and "no match" are the same
 *       answer, so a caller cannot test whether a specific number is registered.
 * </ul>
 *
 * <p>A phone number lookup still tells a caller who tries one number whether it is registered.
 * That is inherent to letting people be found by number at all; what limits it is rate limiting
 * at the edge, which is why the search endpoint is throttled rather than trusted.
 */
@Service
public class UserSearchService {

    private final UserProfileRepository profiles;
    private final UserSearchProperties properties;

    public UserSearchService(UserProfileRepository profiles, UserSearchProperties properties) {
        this.profiles = profiles;
        this.properties = properties;
    }

    /**
     * Searches the directory.
     *
     * @param term a full phone number in E.164, or the start of a display name
     * @param callerId excluded from results: nobody needs to find themselves in a payee list
     * @return matches, possibly empty; never an error for "nothing found"
     */
    @Transactional(readOnly = true)
    public List<UserSummary> search(String term, UUID callerId) {
        String trimmed = term == null ? "" : term.trim();

        if (trimmed.length() < properties.getMinimumTermLength()) {
            // Empty rather than a 400: a caller typing into a search box has not made an error,
            // and the endpoint should say nothing at all until it has enough to go on.
            return List.of();
        }

        if (trimmed.startsWith("+")) {
            return profiles.findByPhoneNumber(trimmed)
                    .filter(profile -> !profile.getUserId().equals(callerId))
                    .map(UserSummary::of)
                    .map(List::of)
                    .orElseGet(List::of);
        }

        return profiles.searchByDisplayNamePrefix(trimmed, callerId, Limit.of(properties.getMaximumResults())).stream()
                .map(UserSummary::of)
                .toList();
    }
}
