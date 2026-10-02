package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/**
 * What a person consents to under CASL (S-108): Northline's own commercial messages, one category per channel —
 * {@code messaging.consent_records.category}, the codes of the email library's
 * {@code MessageClasses.ConsentCategory}. The Account › Notifications row {@code offers} shows them, a cell per channel.
 */
public enum ConsentCategory implements CodedEnum {
    MARKETING_EMAIL("email"),
    MARKETING_SMS("sms"),
    MARKETING_PUSH("push");

    private final String channel;

    ConsentCategory(String channel) {
        this.channel = channel;
    }

    /** The notification column it governs: {@code email | sms | push}. */
    public String channel() {
        return channel;
    }

    public static ConsentCategory ofChannel(String channel) {
        for (var c : values()) {
            if (c.channel.equals(channel)) {
                return c;
            }
        }
        throw new IllegalArgumentException("No consent category for " + channel);
    }
}
