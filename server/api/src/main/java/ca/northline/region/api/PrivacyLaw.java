package ca.northline.region.api;

import ca.northline.shared.CodedEnum;

/**
 * The private-sector privacy law a province's people are told their data is handled under ({@code
 * region.regions.privacy_law}). Federal PIPEDA applies everywhere; a province with a substantially similar act cites
 * its own as well. Which province has which is region data; the web names the law with the province as a parameter.
 */
public enum PrivacyLaw implements CodedEnum {
    PIPEDA,
    AB_PIPA,
    BC_PIPA,
    QC_LAW25;

    /** True when a provincial act applies on top of PIPEDA. */
    public boolean provincial() {
        return this != PIPEDA;
    }
}
