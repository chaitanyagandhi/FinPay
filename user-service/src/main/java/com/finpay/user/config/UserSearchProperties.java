package com.finpay.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on the payee directory.
 *
 * <p>Both values exist to keep a search for someone to pay from becoming a way of enumerating
 * everyone who has an account. Neither is a performance setting.
 */
@ConfigurationProperties(prefix = "finpay.user.search")
public class UserSearchProperties {

    /**
     * How much of a name a caller must know before the directory answers.
     *
     * <p>A one or two character prefix matches a large share of any directory. Requiring three
     * means a caller has to already know roughly who they are looking for.
     */
    private int minimumTermLength = 3;

    /** Capped so a single query cannot page through the user base. */
    private int maximumResults = 20;

    public int getMinimumTermLength() {
        return minimumTermLength;
    }

    public void setMinimumTermLength(int minimumTermLength) {
        this.minimumTermLength = minimumTermLength;
    }

    public int getMaximumResults() {
        return maximumResults;
    }

    public void setMaximumResults(int maximumResults) {
        this.maximumResults = maximumResults;
    }
}
