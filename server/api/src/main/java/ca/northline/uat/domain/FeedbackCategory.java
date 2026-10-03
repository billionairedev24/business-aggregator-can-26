package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;

/** What kind of feedback it is, as the participant files it. */
public enum FeedbackCategory implements CodedEnum {
    BUG,
    CONFUSING,
    IDEA,
    PRAISE
}
