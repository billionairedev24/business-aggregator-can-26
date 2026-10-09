package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;

/**
 * Privileged things a console screen lets a role do (design 03 {@code ROLES.can} and the Data Table's {@code CAN}):
 * opening a screen is {@link ConsoleScreen}, changing something on it is one of these.
 */
public enum ConsoleAction implements CodedEnum {
    SUSPEND,
    DECIDE,
    REFUND,
    PROVINCE,
    PAYOUTS,
    KEYS,
    VERIFY,
    VET,
    DISPATCH,
    SUPPORT,
    /** S-83: write the support desk's reply macros (support leads, admins). */
    MACROS,
    /** S-105: act on privacy requests (record, verify, extend, refuse, start an erasure, apply corrections). */
    PRIVACY,
    /** S-120: onboard pilot businesses (invites, enrolment, owners, blockers, notes, kitchen visits). */
    ONBOARD,
    /** S-121: triage pilot feedback (state, owner, tracker link, duplicates), manage participants, record sign-offs. */
    UAT,
    /** S-118: record a manual go-live gate (pass, fail, not applicable) with its evidence. */
    ATTEST,
    /** Mobile gaps part 2: make promo codes and switch them on and off (finance, admins). */
    PROMOTIONS
}
