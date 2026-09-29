package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.verifications.check_type}. */
public enum CheckType implements CodedEnum {
    KYC,
    REGISTRY,
    LICENCE,
    AHS_PERMIT,
    FOOD_CERT,
    INSPECTION,
    INSURANCE,
    WCB,
    ATTESTATION,
    BANK,
    MFA,
    SITE_VISIT
}
