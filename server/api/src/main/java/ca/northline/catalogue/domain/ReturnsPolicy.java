package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.returns_policy}: Northline standard (14 days, unused) or final sale (perishables only). */
public enum ReturnsPolicy implements CodedEnum {
    STANDARD_14,
    FINAL_SALE
}
