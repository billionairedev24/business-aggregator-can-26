package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.vetting} / {@code services.vetting}: draft (private) → pending (automated checks, then manual review when flagged) → approved | rejected. */
public enum Vetting implements CodedEnum {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED
}
