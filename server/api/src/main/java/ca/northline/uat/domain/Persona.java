package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;

/** Who a pilot participant is in the UAT scripts ({@code docs/uat/}): one script per persona. */
public enum Persona implements CodedEnum {
    PROVIDER,
    SELLER,
    KITCHEN,
    CUSTOMER,
    COURIER,
    STAFF;

    /** A business takes part as a whole (every member sees the feedback control); the others are people. */
    public boolean business() {
        return this == PROVIDER || this == SELLER || this == KITCHEN;
    }
}
