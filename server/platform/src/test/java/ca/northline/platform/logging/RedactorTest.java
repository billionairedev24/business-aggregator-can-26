package ca.northline.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** S-112: what the logs may never carry, and what they must keep readable. */
class RedactorTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            email                     | Invitation sent to amara.osei+test@example.ca today | Invitation sent to [EMAIL] today
            email in JSON             | {"email":"Ravi.Sandhu@prairie-wrench.co.uk"}        | {"email":"[EMAIL]"}
            phone, punctuated         | Call (587) 555-0101 back                            | Call [PHONE] back
            phone, dotted             | mobile 403.555.0199.                                | mobile [PHONE].
            phone, E.164              | to=+15875550101 ok                                  | to=[PHONE] ok
            phone, +1 spaced          | for +1 587 555 0101: done                           | for [PHONE]: done
            phone already masked      | to +1 403 *** **48: sent                            | to +1 403 *** **48: sent
            card, spaced              | card 4242 4242 4242 4242 declined                    | card [CARD …4242] declined
            card, dashed              | pan 5555-5555-5555-4444                              | pan [CARD …4444]
            card, plain               | 378282246310005 amex                                 | [CARD …0005] amex
            not a card (Luhn)         | order 1234567890123 total                            | order 1234567890123 total
            postal code               | ships to T2P 1B5, Calgary                            | ships to T2P ***, Calgary
            postal code, no space     | H2X1Y4                                               | H2X ***
            bearer                    | Authorization: Bearer abc.def-ghi_jkl123             | Authorization: [REDACTED]
            bearer in prose           | sent bearer abcdefghijklmnop to api                  | sent bearer [REDACTED] to api
            jwt                       | token eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl here | token [REDACTED] here
            stripe secret             | key sk_live_51Habcdefghijk                           | key sk_live_[REDACTED]
            stripe webhook secret     | whsec_abcdef0123456789                               | whsec_[REDACTED]
            openrouter key            | using sk-or-v1-0123456789abcdef                      | using sk-[REDACTED]
            key=value secret          | client_secret=s3cr3t-value&grant_type=x              | client_secret=[REDACTED]&grant_type=x
            json password             | {"password":"hunter2","name":"x"}                    | {"password":"[REDACTED]","name":"x"}
            otp code (S-20 stand-in)  | [SMS] Verification code for +1 587 555 0101: 482913 (not sent — local SMS fake) | [SMS] Verification code for [PHONE]: [CODE] (not sent — local SMS fake)
            otp in the SMS text       | Northline: your verification code is 482913. It expires in 10 minutes. | Northline: your verification code is [CODE]. It expires in 10 minutes.
            otp in French             | Northline : votre code de vérification est 482913. | Northline : votre code de vérification est [CODE].
            ulid stays                | merchant 01J9ZD3V00000000000000PWM1 renamed          | merchant 01J9ZD3V00000000000000PWM1 renamed
            stripe id stays           | payout po_1234567890 sent, pi_3Nabc                  | payout po_1234567890 sent, pi_3Nabc
            amounts and dates stay    | 81437 cents at 2026-09-30T15:00:00Z, 10 minutes      | 81437 cents at 2026-09-30T15:00:00Z, 10 minutes
            trace ids stay            | trace 4bf92f3577b34da6a3ce929d0e0e4736 span 00f067aa0ba902b7 | trace 4bf92f3577b34da6a3ce929d0e0e4736 span 00f067aa0ba902b7
            """)
    void masks(String rule, String input, String expected) {
        assertThat(Redactor.redact(input)).isEqualTo(expected);
    }

    @Test
    void masksAPrivateKeyWhole() {
        var pem = "key:\n-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49\n-----END PRIVATE KEY-----\nend";
        assertThat(Redactor.redact(pem)).isEqualTo("key:\n[REDACTED]\nend");
    }

    @Test
    void sensitiveFieldsAreMaskedWhole() {
        assertThat(Redactor.redact("password", "anything at all")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redact("http.request.header.authorization", "x")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redact("otp", "123456")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redact("verification_code", "123456")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redact("message", "hello amara@example.ca")).isEqualTo("hello [EMAIL]");
    }

    @Test
    void correlationAndStatusFieldsAreNotSecrets() {
        assertThat(Redactor.isSensitiveName("trace.id")).isFalse();
        assertThat(Redactor.isSensitiveName("span.id")).isFalse();
        assertThat(Redactor.isSensitiveName("eventId")).isFalse();
        assertThat(Redactor.isSensitiveName("http.response.status_code")).isFalse();
        assertThat(Redactor.isSensitiveName("error.code")).isFalse();
        assertThat(Redactor.isSensitiveName("consumer")).isFalse();
        assertThat(Redactor.isSensitiveName("span")).isFalse();
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.redact("")).isEmpty();
        assertThat(Redactor.redact("password", null)).isNull();
    }

    @Test
    void luhn() {
        assertThat(Redactor.luhn("4242424242424242")).isTrue();
        assertThat(Redactor.luhn("4242424242424241")).isFalse();
    }
}
