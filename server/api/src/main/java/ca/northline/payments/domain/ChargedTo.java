package ca.northline.payments.domain;

import ca.northline.shared.CodedEnum;

/** {@code payments.refunds.charged_to}: who funds a refund. */
public enum ChargedTo implements CodedEnum {
    MERCHANT,
    PLATFORM
}
