package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/**
 * Province or territory the business operates in (Account step), by its two-letter code. Which of them a business may
 * pick — and how each is labelled (live, pilot, waitlist) — is the region model's launch status (S-134), not this list.
 */
public enum Province implements CodedEnum {
    AB,
    BC,
    MB,
    NB,
    NL,
    NS,
    NT,
    NU,
    ON,
    PE,
    QC,
    SK,
    YT;

    @Override
    public String code() {
        return name();
    }
}
