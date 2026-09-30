package ca.northline.auth.sms;

import ca.northline.sms.twilio.TwilioSmsTransport;

/**
 * {@code northline.sms.provider=twilio}: codes through the shared Twilio adapter ({@link TwilioSmsTransport}: SMS via
 * Programmable Messaging, voice via a call reading inline TwiML with the Polly voice of the language). Codes are
 * generated and checked by northline-auth itself (rules, rate limits, audit), so Twilio Verify isn't used — see
 * DECISIONS.md (S-8). No retry: a retried request can deliver twice, and the person can resend.
 */
final class TwilioSmsSender extends TransportSmsSender {

    TwilioSmsSender(TwilioSmsTransport transport) {
        super(transport);
    }
}
