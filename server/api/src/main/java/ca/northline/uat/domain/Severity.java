package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;

/**
 * How serious the participant thinks it is. Staff decide whether it blocks the launch at triage
 * ({@link FeedbackState#ACCEPTED} with {@code blocking}); a participant's {@link #BLOCKER} that nobody has triaged yet
 * holds the go/no-go too.
 */
public enum Severity implements CodedEnum {
    /** "I can't finish what I was doing." */
    BLOCKER,
    /** "I finished, but it was hard or wrong." */
    MAJOR,
    MINOR,
    COSMETIC
}
