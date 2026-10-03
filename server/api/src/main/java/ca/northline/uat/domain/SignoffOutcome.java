package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;

/** A participant's verdict on a UAT script (the printed sign-off form's three boxes). */
public enum SignoffOutcome implements CodedEnum {
    SIGNED_OFF,
    /** Signed off, with comments that don't block. */
    WITH_COMMENTS,
    /** Not signed off: blocking items named. */
    BLOCKED;

    public boolean signed() {
        return this != BLOCKED;
    }
}
