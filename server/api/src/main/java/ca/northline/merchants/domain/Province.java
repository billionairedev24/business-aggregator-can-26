package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** Province the business operates in (Account step). Alberta is live, BC a pilot, ON/QC waitlisted. */
public enum Province implements CodedEnum {
    AB,
    BC,
    ON,
    QC;

    @Override
    public String code() {
        return name();
    }
}
