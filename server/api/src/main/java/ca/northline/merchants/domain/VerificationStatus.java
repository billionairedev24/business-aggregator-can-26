package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.verifications.status}. */
public enum VerificationStatus implements CodedEnum {
    TODO,
    SUBMITTED,
    VERIFIED,
    EXPIRED,
    REJECTED;

    /** Counts towards "N of M complete" and lets the owner submit for review. */
    public boolean complete() {
        return this == SUBMITTED || this == VERIFIED;
    }
}
