package ca.northline.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.platform.SmsProperties;
import ca.northline.sms.SmsKeywords.Keyword;
import ca.northline.sms.twilio.TwilioSignature;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** S-157: inbound SMS webhooks — Twilio's signature, the opt-out / opt-in keywords, the verifier per provider. */
class SmsWebhooksTest {

    /** Twilio's documented example (Webhooks security): auth token 12345 over this URL and these parameters. */
    static final String DOC_URL = "https://mycompany.com/myapp.php?foo=1&bar=2";

    static final Map<String, List<String>> DOC_PARAMS = Map.of(
            "CallSid", List.of("CA1234567890ABCDE"),
            "Caller", List.of("+12349013030"),
            "Digits", List.of("1234"),
            "From", List.of("+12349013030"),
            "To", List.of("+18005551212"));

    @Test
    void twilioSignature_matchesTwiliosDocumentedExample() {
        assertThat(TwilioSignature.sign("12345", DOC_URL, DOC_PARAMS)).isEqualTo("0/KCTR6DLpKmkAf8muzZqo1nDgQ=");
        assertThat(TwilioSignature.valid("12345", DOC_URL, DOC_PARAMS, "0/KCTR6DLpKmkAf8muzZqo1nDgQ="))
                .isTrue();
        assertThat(TwilioSignature.valid("12345", DOC_URL, DOC_PARAMS, "0/KCTR6DLpKmkAf8muzZqo1nDgQ"))
                .isFalse();
        assertThat(TwilioSignature.valid("other-token", DOC_URL, DOC_PARAMS, "0/KCTR6DLpKmkAf8muzZqo1nDgQ="))
                .isFalse();
        assertThat(TwilioSignature.valid("12345", DOC_URL.replace("foo=1", "foo=2"), DOC_PARAMS, "x"))
                .isFalse();
        assertThat(TwilioSignature.valid("12345", DOC_URL, DOC_PARAMS, null)).isFalse();
    }

    @Test
    void keywords_inBothLanguages_caseAccentsAndPunctuationAside() {
        for (var stop :
                List.of("STOP", "stop", " Stop. ", "ARRET", "Arrêt!", "UNSUBSCRIBE", "DÉSABONNER", "désabonner")) {
            assertThat(SmsKeywords.of(stop)).as(stop).contains(Keyword.STOP);
        }
        for (var start : List.of("START", "start", "UNSTOP", "Oui")) {
            assertThat(SmsKeywords.of(start)).as(start).contains(Keyword.START);
        }
        assertThat(SmsKeywords.of("aide")).contains(Keyword.HELP);
        assertThat(SmsKeywords.of("please stop texting me")).isEmpty();
        assertThat(SmsKeywords.of("")).isEmpty();
    }

    @Test
    void verifierPerProvider() {
        var twilio = SmsWebhooks.of(
                        new SmsProperties(
                                SmsProperties.Provider.TWILIO, "+15875550100", null, "ACx", "12345", null, null),
                        new MockEnvironment())
                .orElseThrow();
        assertThat(twilio.verified(DOC_URL, DOC_PARAMS, "0/KCTR6DLpKmkAf8muzZqo1nDgQ="))
                .isTrue();
        assertThat(twilio.verified(DOC_URL, DOC_PARAMS, "forged")).isFalse();

        var local = new SmsProperties(SmsProperties.Provider.LOCAL, null, null, null, null, null, null);
        var dev = new MockEnvironment();
        dev.setActiveProfiles("local");
        assertThat(SmsWebhooks.of(local, dev).orElseThrow().verified(DOC_URL, DOC_PARAMS, null))
                .isTrue();
        var dev2 = new MockEnvironment();
        dev2.setActiveProfiles("dev");
        assertThat(SmsWebhooks.of(local, dev2)).isEmpty(); // the unsigned fake never outside local/test
        assertThat(SmsWebhooks.of(
                        new SmsProperties(SmsProperties.Provider.AWS, "+1", null, null, null, null, null),
                        new MockEnvironment()))
                .isEqualTo(Optional.empty());
        assertThatThrownBy(() -> SmsWebhooks.of(
                        new SmsProperties(SmsProperties.Provider.TWILIO, "+1", null, "ACx", null, null, null),
                        new MockEnvironment()))
                .hasMessageContaining("SMS_AUTH_TOKEN");
    }
}
