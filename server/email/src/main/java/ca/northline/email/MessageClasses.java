package ca.northline.email;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The CASL class of every message Northline sends (S-108), in one place for the api and the worker: commercial
 * electronic messages need the recipient's consent, an identified sender and an unsubscribe mechanism (CASL s. 6);
 * transactional and relationship messages — confirmations, receipts, updates on something the person asked for,
 * security and account notices (s. 6(6)) — are exempt from consent but still identify the sender.
 *
 * <ul>
 *   <li>Emails: each template's {@link EmailContent.Purpose} (compile-time: every {@link EmailContent} declares one;
 *       {@code MessageClassesTest} fails on a template file without a record and sample).
 *   <li>Notification rows (Studio › Settings › Notifications for team members, Account › Notifications for customers):
 *       {@link #ROWS}, keyed {@code team.<row>} / {@code customer.<row>}; the api's and the worker's tests fail on a row
 *       that is not here.
 *   <li>Messages outside the matrix: {@link #OTHER} ({@code sms:…}, {@code push:…}), checked by the sending app's tests.
 * </ul>
 *
 * Merchants' own marketing to their customers is not sent by the platform (the Privacy Policy forbids businesses to
 * add customers to marketing lists), so it has no class here.
 */
public final class MessageClasses {

    /** CASL class. */
    public enum MessageClass {
        /** Transactional or relationship (CASL s. 6(6)): no consent needed; the sender is still identified. */
        TRANSACTIONAL,
        /** Commercial electronic message: express consent, sender identification and unsubscribe required. */
        COMMERCIAL
    }

    /**
     * What a person consents to (S-108): Northline's own commercial messages, per channel. Codes are stored in
     * {@code messaging.consent_records.category}.
     */
    public enum ConsentCategory {
        MARKETING_EMAIL("marketing_email", "email"),
        MARKETING_SMS("marketing_sms", "sms"),
        MARKETING_PUSH("marketing_push", "push");

        private final String code;
        private final String channel;

        ConsentCategory(String code, String channel) {
            this.code = code;
            this.channel = channel;
        }

        public String code() {
            return code;
        }

        /** The Settings › Notifications column it governs: {@code email | sms | push}. */
        public String channel() {
            return channel;
        }

        public static Optional<ConsentCategory> ofCode(String code) {
            return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
        }

        public static ConsentCategory ofChannel(String channel) {
            return Arrays.stream(values())
                    .filter(c -> c.channel.equals(channel))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No consent category for channel " + channel));
        }
    }

    /** Every notification row of both matrices. */
    public static final Map<String, MessageClass> ROWS = rows();

    /** Messages outside the matrices and the email templates. */
    public static final Map<String, MessageClass> OTHER = Map.of(
            // api: an owner invited a mobile number to the team (S-27)
            "sms:team-invitation", MessageClass.TRANSACTIONAL,
            // northline-auth: sign-in and step-up codes by SMS or voice (S-8)
            "sms:verification-code", MessageClass.TRANSACTIONAL,
            // api: the code that verifies a privacy request (S-105)
            "sms:privacy-code", MessageClass.TRANSACTIONAL,
            // worker: a courier's run was assigned or moved (S-102)
            "push:courier-run", MessageClass.TRANSACTIONAL);

    private MessageClasses() {}

    /** The class of a team ({@code team}) or customer ({@code customer}) notification row; empty = unclassified. */
    public static Optional<MessageClass> ofRow(String audience, String row) {
        return Optional.ofNullable(ROWS.get(audience + "." + row));
    }

    /** Whether a customer or team row is commercial; an unclassified row is treated as commercial (fail closed). */
    public static boolean commercialRow(String audience, String row) {
        return ofRow(audience, row).orElse(MessageClass.COMMERCIAL) == MessageClass.COMMERCIAL;
    }

    public static MessageClass of(EmailContent content) {
        return content.purpose() == EmailContent.Purpose.COMMERCIAL
                ? MessageClass.COMMERCIAL
                : MessageClass.TRANSACTIONAL;
    }

    private static Map<String, MessageClass> rows() {
        var t = MessageClass.TRANSACTIONAL;
        var rows = new LinkedHashMap<String, MessageClass>();
        // Studio › Settings › Notifications (design 02): the business's own operations — relationship messages
        rows.put("team.new_booking", t);
        rows.put("team.quote_request", t);
        rows.put("team.customer_message", t);
        rows.put("team.payout", t);
        rows.put("team.dispute", t);
        rows.put("team.low_stock", t);
        rows.put("team.quality", t);
        // Account › Notifications (design 06): updates on what the customer bought or asked for
        rows.put("customer.booking_reminders", t);
        rows.put("customer.order_updates", t);
        rows.put("customer.sign_off", t);
        rows.put("customer.quotes_messages", t);
        rows.put("customer.refunds_cases", t);
        rows.put("customer.security", t);
        // "Offers & rewards": promotes Northline — commercial, consent per channel
        rows.put("customer.offers", MessageClass.COMMERCIAL);
        return Collections.unmodifiableMap(rows);
    }
}
