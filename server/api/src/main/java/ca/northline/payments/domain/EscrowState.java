package ca.northline.payments.domain;

import ca.northline.shared.CodedEnum;

/** {@code payments.escrows.state}. {@code DISPUTED} = on hold while a dispute or refund case is reviewed. */
public enum EscrowState implements CodedEnum {
    HELD,
    RELEASED,
    REFUNDED,
    DISPUTED
}
