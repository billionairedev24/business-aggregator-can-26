package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;
import java.util.Set;

/** Where a consent was given or withdrawn ({@code messaging.consent_records.source}, S-108). */
public enum ConsentSource implements CodedEnum {
    WEB_SIGNUP,
    APP_SIGNUP,
    WEB_SETTINGS,
    APP_SETTINGS,
    /** The checkout's opt-in checkbox. */
    CHECKOUT,
    /** A team member in Studio › Settings › Notifications. */
    STUDIO,
    /** Consents brought in from another list (with their original evidence); none so far. */
    IMPORT,
    /** The unsubscribe page behind a message's link (the person pressed the button). */
    UNSUBSCRIBE_LINK,
    /** A mailbox provider's one-click unsubscribe (RFC 8058 POST). */
    LIST_UNSUBSCRIBE,
    /** A STOP / ARRET reply to an SMS. Reserved: the SMS port receives no inbound messages yet. */
    SMS_KEYWORD,
    /** Staff on the person's behalf (a withdrawal by phone, mail or email). */
    CONSOLE,
    /** The account was erased (S-105): every consent is withdrawn. */
    ERASURE;

    /** What a signed-in person may name as the place they consented or withdrew. */
    public static final Set<ConsentSource> PERSONAL =
            Set.of(WEB_SIGNUP, APP_SIGNUP, WEB_SETTINGS, APP_SETTINGS, CHECKOUT, STUDIO);
}
