package ca.northline.golive.domain;

import ca.northline.shared.CodedEnum;

/** A gate's state on the checklist: only {@code pass} and {@code not_applicable} let a required gate through. */
public enum GateStatus implements CodedEnum {
    PASS,
    FAIL,
    /** Not known yet: a manual gate nobody recorded (or recorded too long ago), an automatic one without its source. */
    PENDING,
    NOT_APPLICABLE;

    public boolean clears() {
        return this == PASS || this == NOT_APPLICABLE;
    }
}
