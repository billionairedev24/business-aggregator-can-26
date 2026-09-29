package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/** {@code messaging.messages.sender_role}: which side wrote a message. The business side is {@code MERCHANT}. */
public enum SenderRole implements CodedEnum {
    MERCHANT,
    CUSTOMER,
    AGENT,
    SYSTEM
}
