package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.registry_checks.outcome}. Everything but {@code matched} goes to a Northline agent. */
public enum RegistryOutcome implements CodedEnum {
    /** Found, active, not expired, and the name matches. */
    MATCHED,
    /** Found, but the name differs, it isn't in good standing, or it expired ({@code reasons}). */
    MISMATCH,
    NOT_FOUND,
    /** The source has no API (or the provider answers later): an agent looks it up. */
    MANUAL,
    /** The source couldn't be reached; an agent looks it up (a re-check tries again later). */
    UNAVAILABLE
}
