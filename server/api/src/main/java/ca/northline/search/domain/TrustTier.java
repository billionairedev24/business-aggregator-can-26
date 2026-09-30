package ca.northline.search.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.tier}: the trust ladder results are boosted by (master ranks above trusted above registered). */
public enum TrustTier implements CodedEnum {
    REGISTERED,
    TRUSTED,
    MASTER
}
