package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.merchant_principals.role}. */
public enum PrincipalRole implements CodedEnum {
    OWNER,
    PARTNER,
    PARTNER_SIGNING,
    DIRECTOR,
    OFFICER,
    SHAREHOLDER,
    CHAIR,
    PRESIDENT,
    TREASURER,
    SECRETARY
}
