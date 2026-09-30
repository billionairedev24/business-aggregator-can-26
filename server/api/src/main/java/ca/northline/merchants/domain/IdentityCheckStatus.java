package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.owner_identity_checks.status}: where one owner is in their Stripe Identity verification. */
public enum IdentityCheckStatus implements CodedEnum {
    /** A session is open; the owner hasn't submitted anything yet. */
    PENDING,
    /** Submitted; Stripe is checking the document and selfie. */
    PROCESSING,
    /** Stripe verified the document and selfie and the name (and date of birth, when known) match. */
    VERIFIED,
    /** Stripe couldn't verify it ({@code last_error}); the owner starts a new session. */
    RETRY,
    /** Stripe verified the person, but the name or date of birth differs from the application: a Northline agent decides. */
    REVIEW,
    /** The session was canceled (by Northline replacing it, or at Stripe). */
    CANCELED;

    /** Handed in: nothing more for the owner to do right now. */
    public boolean handedIn() {
        return this == PROCESSING || this == VERIFIED || this == REVIEW;
    }
}
