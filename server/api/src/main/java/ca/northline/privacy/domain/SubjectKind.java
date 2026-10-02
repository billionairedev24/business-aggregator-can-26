package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;

/** Whom the request is about: a customer, or someone on a business's team (owner or staff) — perhaps both. */
public enum SubjectKind implements CodedEnum {
    CUSTOMER,
    TEAM
}
