package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;

/** Why staff refused a request (the person is told, with the regulator they can complain to). */
public enum Decision implements CodedEnum {
    IDENTITY_NOT_VERIFIED,
    NOT_OUR_DATA,
    LEGAL_EXCEPTION,
    DUPLICATE,
    FRIVOLOUS
}
