package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.fulfilment}: Northline pooled delivery, install at a service visit, customer pickup, Canada Post. */
public enum Fulfilment implements CodedEnum {
    POOLED,
    INSTALL,
    PICKUP,
    SHIP
}
