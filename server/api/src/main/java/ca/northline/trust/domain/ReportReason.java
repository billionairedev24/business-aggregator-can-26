package ca.northline.trust.domain;

import ca.northline.shared.CodedEnum;

/** {@code trust.reviews.report_reason}. {@code OTHER} needs a note. */
public enum ReportReason implements CodedEnum {
    FAKE,
    OFFENSIVE,
    PERSONAL_INFO,
    WRONG_BUSINESS,
    OTHER
}
