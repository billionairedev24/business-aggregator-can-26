package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/**
 * {@code merchants.owner_identity_checks.name_match | dob_match}: how what Stripe Identity read compares with what
 * Northline knows. Only this result is kept, never the values themselves.
 */
public enum IdentityMatch implements CodedEnum {
    MATCH,
    MISMATCH,
    /** Nothing to compare with (no Stripe Connect person with a date of birth yet, or no verified output). */
    UNAVAILABLE
}
