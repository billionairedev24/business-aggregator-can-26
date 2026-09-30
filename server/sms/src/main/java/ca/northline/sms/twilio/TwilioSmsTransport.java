package ca.northline.sms.twilio;

import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.sms.SmsDeliveryFailed.Kind;
import ca.northline.sms.SmsTransport;
import ca.northline.sms.SpokenLanguage;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code northline.sms.provider=twilio}: SMS through Programmable Messaging ({@code From} = a number, or
 * {@code MessagingServiceSid} when the sender starts with {@code MG}), voice through a call that reads the text
 * (inline TwiML {@code <Say>} with the Amazon Polly voice of the language — Twilio's legacy {@code alice} voice is
 * retired). Plain REST ({@link TwilioApi}), no Twilio SDK.
 *
 * <p>Error codes that mean "this number can't get it" become {@link Kind#UNDELIVERABLE_NUMBER}; everything else
 * (credentials, sender, geo permissions, throttling, 5xx, time-outs) is {@link Kind#PROVIDER_UNAVAILABLE}.
 */
@Slf4j
public final class TwilioSmsTransport implements SmsTransport {

    /**
     * 21211 invalid To · 21214 To can't be reached · 21217 not a valid number for calls · 21401 invalid number · 21408
     * is a geo permission (ours), not here · 21610 recipient replied STOP · 21612 To unreachable by SMS · 21614 not a
     * mobile number · 13223 / 13224 invalid number for a call.
     */
    public static final Set<Integer> UNDELIVERABLE =
            Set.of(21211, 21214, 21217, 21401, 21610, 21612, 21614, 13223, 13224);

    private final TwilioApi api;
    private final String accountSid;
    private final String from;
    private final String voiceFrom;

    public TwilioSmsTransport(TwilioApi api, String accountSid, String from, String voiceFrom) {
        this.api = api;
        this.accountSid = accountSid;
        this.from = from;
        this.voiceFrom = voiceFrom;
    }

    @Override
    public String sendText(String to, String body) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("To", to);
        form.add(from.startsWith("MG") ? "MessagingServiceSid" : "From", from);
        form.add("Body", body);
        return request("SMS", to, () -> api.sendMessage(accountSid, form));
    }

    @Override
    public String call(String to, String spokenText, Locale locale) {
        var language = SpokenLanguage.of(locale);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("To", to);
        form.add("From", voiceFrom);
        form.add("Twiml", """
                <Response><Pause length="1"/><Say voice="Polly.%s" language="%s">%s</Say></Response>""".formatted(
                        language.pollyVoice(), language.languageTag(), HtmlUtils.htmlEscape(spokenText, "UTF-8")));
        return request("VOICE", to, () -> api.createCall(accountSid, form));
    }

    private String request(String channel, String to, Supplier<TwilioApi.Resource> call) {
        try {
            var created = call.get();
            log.info(
                    "Twilio {} to {} accepted: {} {}",
                    channel,
                    PhoneNumbers.masked(to),
                    created.sid(),
                    created.status());
            return created.sid();
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

    private static TwilioApi.Failure errorOf(RestClientResponseException e) {
        try {
            var body = e.getResponseBodyAs(TwilioApi.Failure.class);
            return body != null ? body : new TwilioApi.Failure(null, e.getStatusText());
        } catch (RuntimeException _) {
            return new TwilioApi.Failure(null, e.getStatusText());
        }
    }
}
