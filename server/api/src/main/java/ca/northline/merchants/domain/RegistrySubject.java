package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.registry_checks.subject}: what kind of record is looked up. */
public enum RegistrySubject implements CodedEnum {
    CORPORATION,
    EXTRA_PROVINCIAL,
    PARTNERSHIP,
    TRADE_NAME,
    COOPERATIVE,
    SOCIETY,
    MUNICIPAL_LICENCE,
    LICENCE
}
