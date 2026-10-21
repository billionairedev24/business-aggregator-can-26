package ca.northline.messaging.application;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-157: what the SMS provider tells Northline (inbound webhooks, already signature-verified): a text a person sent to
 * Northline's number — STOP / ARRET withdraws the text-message consent of every account with that number, START gives
 * it back after a STOP, HELP says who is texting — and the delivery status of a text Northline sent.
 */
public interface InboundSms {

    /**
     * @param provider {@code twilio} | {@code local}
     * @param messageId the provider's id of the text (Twilio {@code MessageSid}): one text is applied and answered once
     * @param from the sender, E.164
     */
    record Received(String provider, String messageId, String from, String body) {}

    /** The reply to send back (Twilio: in the webhook's TwiML answer); empty = none (a retry, or not a keyword). */
    Optional<String> received(Received text);

    /** A delivery status callback ({@code queued → sent → delivered | undelivered | failed}). */
    record Status(
            String provider,
            String messageId,
            String status,
            @Nullable String errorCode) {}

    void status(Status status);

    /** Port: {@code messaging.sms_inbound} and {@code messaging.sms_delivery_status} (V453). */
    interface SmsWebhookLog {

        /** Records the text unless it was seen (provider, message id); true = first time. */
        boolean first(String provider, String messageId, String fromHash, String keyword, Instant at);

        void applied(String provider, String messageId, int accounts);

        /** The latest status, unless a final one is already stored (a late {@code sent} never replaces {@code delivered}). */
        void status(Status status, Instant at);

        int purgeBefore(Instant before);
    }
}
