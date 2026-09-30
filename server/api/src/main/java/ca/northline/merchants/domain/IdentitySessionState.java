package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** Status of a Stripe Identity VerificationSession ({@code requires_input | processing | verified | canceled}). */
public enum IdentitySessionState implements CodedEnum {
    REQUIRES_INPUT,
    PROCESSING,
    VERIFIED,
    CANCELED
}
