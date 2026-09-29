package ca.northline.auth.sms;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsDeliveryFailed.Kind;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code northline.sms.provider=twilio}: SMS through Programmable Messaging, voice through a call that reads the code
 * (inline TwiML {@code <Say>} with the Amazon Polly voice of the language: {@code Polly.Joanna} en-US,
 * {@code Polly.Chantal} fr-CA — Twilio's legacy {@code alice} voice is retired). Codes are generated and checked
 * by northline-auth itself (rules, rate limits, audit), so Twilio Verify isn't used — see DECISIONS.md (S-8).
 *
 * <p>Error codes that mean "this number can't get it" become {@link Kind#UNDELIVERABLE_NUMBER}; everything else
 * (credentials, sender, geo permissions, throttling, 5xx, time-outs) is {@link Kind#PROVIDER_UNAVAILABLE}. No retry:
 * a retried request can deliver twice, and the person can resend.
 */
@Slf4j
@RequiredArgsConstructor
class TwilioSmsSender implements SmsSender {

    /**
     * 21211 invalid To · 21214 To can't be reached · 21217 not a valid number for calls · 21401 invalid number · 21408
     * is a geo permission (ours), not here · 21610 recipient replied STOP · 21612 To unreachable by SMS · 21614 not a
     * mobile number · 13223 / 13224 invalid number for a call.
     */
    static final Set<Integer> UNDELIVERABLE = Set.of(21211, 21214, 21217, 21401, 21610, 21612, 21614, 13223, 13224);

    private final TwilioApi api;
    private final String accountSid;
    private final String from;
    private final String voiceFrom;

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
        var messages = CodeMessages.of(locale);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("To", to.e164());
        try {
            var created = switch (channel) {
                case SMS -> {
                    form.add(from.startsWith("MG") ? "MessagingServiceSid" : "From", from);
                    form.add("Body", messages.sms(code));
                    yield api.sendMessage(accountSid, form);
                }
                case VOICE -> {
                    form.add("From", voiceFrom);
                    form.add("Twiml", twiml(messages, code));
                    yield api.createCall(accountSid, form);
                }
            };
            log.info("Twilio {} to {} accepted: {} {}", channel, to.masked(), created.sid(), created.status());
        } catch (RestClientResponseException e) {
            var error = errorOf(e);
            var kind = error.code() != null && UNDELIVERABLE.contains(error.code())
                    ? Kind.UNDELIVERABLE_NUMBER
                    : Kind.PROVIDER_UNAVAILABLE;
            throw new SmsDeliveryFailed(
                    kind,
                    "Twilio %s %d: %s %s".formatted(channel, e.getStatusCode().value(), error.code(), error.message()),
                    e);
        } catch (ResourceAccessException e) {
            throw new SmsDeliveryFailed(Kind.PROVIDER_UNAVAILABLE, "Twilio unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new SmsDeliveryFailed(
                    Kind.PROVIDER_UNAVAILABLE, "Twilio answer not understood: " + e.getMessage(), e);
        }
    }

    private static String twiml(CodeMessages messages, String code) {
        return """
                <Response><Pause length="1"/><Say voice="Polly.%s" language="%s">%s</Say></Response>""".formatted(
                messages.pollyVoice(), messages.languageTag(), HtmlUtils.htmlEscape(messages.voice(code), "UTF-8"));
    }

    private static TwilioApi.Failure errorOf(RestClientResponseException e) {
        try {
            var body = e.getResponseBodyAs(TwilioApi.Failure.class);
            return body != null ? body : new TwilioApi.Failure(null, e.getStatusText());
        } catch (RuntimeException _) {
            return new TwilioApi.Failure(null, e.getStatusText());
        }
    }
}
