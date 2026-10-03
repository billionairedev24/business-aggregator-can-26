package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;

/** Where the feedback was sent from. */
public enum FeedbackApp implements CodedEnum {
    STUDIO,
    CONSUMER,
    CONSOLE,
    /** The consumer mobile app. */
    MOBILE,
    COURIER
}
