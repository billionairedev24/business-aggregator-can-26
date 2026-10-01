package ca.northline.trust.api;

import java.util.Optional;

/**
 * S-93: the restricted keywords listing vetting checks (trust &amp; safety rules, console {@code trust}): a listing whose
 * words contain one goes to the console's vetting queue ({@code restricted_keyword}).
 */
public interface ListingKeywordRules {

    /** The first restricted word or phrase found in {@code text} (case-insensitive), if any. */
    Optional<String> restrictedTerm(String text);
}
