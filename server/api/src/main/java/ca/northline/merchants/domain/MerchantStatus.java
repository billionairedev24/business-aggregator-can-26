package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.merchants.status}. */
public enum MerchantStatus implements CodedEnum {
    APPLICANT,
    PENDING,
    ACTIVE,
    PAUSED,
    SUSPENDED
}
