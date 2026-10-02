package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.support.AuthIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/** Create account: form → phone code → authenticator app → account (validation-rules.md § Registration). */
class RegistrationApiTest extends AuthIntegrationTest {

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void happyPath_createsAccountWithTotpAndSignsIn() throws Exception {
        var person = newPerson();
        var session = new MockHttpSession();

        postJson("/api/auth/register", session, person.json())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("otp"))
                .andExpect(jsonPath("$.resendAfterSeconds").value(45))
                .andExpect(jsonPath("$.channel").value("sms"));
        assertThat(sms.sentTo(person.e164())).hasSize(1);
        // Nothing is written before the second factor is confirmed.
        assertThat(userCount(person.email())).isZero();

        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("mfa"));

        var setup = mvc.perform(post("/api/auth/register/totp").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/")))
                .andExpect(jsonPath("$.qrCode").value(startsWith("data:image/png;base64,")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = com.jayway.jsonpath.JsonPath.read(setup, "$.secret");

        postJson("/api/auth/register/totp/verify", session, json(Map.of("code", totpNow(secret))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.firstName").value("Amara"))
                .andExpect(jsonPath("$.user.lastName").value("Osei"))
                .andExpect(jsonPath("$.user.phone").value(person.e164()))
                .andExpect(jsonPath("$.user.initials").value("AO"))
                .andExpect(jsonPath("$.acr").value("mfa"));

        var row = jdbc.sql("""
                        SELECT first_name, last_name, phone, mfa_primary, terms_version,
                               phone_verified_at IS NOT NULL AS verified, terms_accepted_at IS NOT NULL AS accepted
                          FROM identity.users WHERE email = CAST(:e AS citext)""").param("e", person.email()).query().singleRow();
        assertThat(row)
                .containsEntry("first_name", "Amara")
                .containsEntry("last_name", "Osei")
                .containsEntry("phone", person.e164())
                .containsEntry("mfa_primary", "totp")
                .containsEntry("terms_version", "3.0")
                .containsEntry("verified", true)
                .containsEntry("accepted", true);

        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").value("mfa"));
    }

    @Test
    void registrationSignInIsLogged() throws Exception {
        var done = register(newPerson());
        assertThat(jdbc.sql("SELECT count(*) FROM identity.sessions WHERE user_id = :u AND method = 'registration'")
                        .param("u", done.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = 'auth.sign_in'")
                        .param("u", done.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * S-116 (Loi 96): the language the Terms were accepted in is kept with the acceptance, and an express request for
     * the English version (where they come in French first) is recorded with its time.
     */
    @Test
    void termsLanguageAndAnExpressRequestForEnglishAreRecorded() throws Exception {
        var french = newPerson();
        completeRegistration(
                french, french.json().replace("\"terms\":true", "\"terms\":true,\"termsLanguage\":\"fr\""));
        assertThat(termsOf(french.email()))
                .containsEntry("terms_language", "fr")
                .containsEntry("english_requested", false);

        var english = newPerson();
        completeRegistration(
                english,
                english.json()
                        .replace(
                                "\"terms\":true",
                                "\"terms\":true,\"termsLanguage\":\"fr\",\"termsEnglishRequested\":true"));
        assertThat(termsOf(english.email()))
                .containsEntry("terms_language", "en") // asking for English means it was shown in English
                .containsEntry("english_requested", true);

        var unsaid = newPerson();
        completeRegistration(unsaid, unsaid.json());
        assertThat(termsOf(unsaid.email())).containsEntry("terms_language", null);

        postJson(
                        "/api/auth/register",
                        new MockHttpSession(),
                        newPerson().json().replace("\"terms\":true", "\"terms\":true,\"termsLanguage\":\"de\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='termsLanguage')].message")
                        .value(AuthMessages.TERMS_LANGUAGE));
    }

    private void completeRegistration(Person person, String form) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/register", session, form).andExpect(status().isOk());
        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                .andExpect(status().isOk());
        var setup = mvc.perform(post("/api/auth/register/totp").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = com.jayway.jsonpath.JsonPath.read(setup, "$.secret");
        postJson("/api/auth/register/totp/verify", session, json(Map.of("code", totpNow(secret))))
                .andExpect(status().isCreated());
        clock.advanceSeconds(ca.northline.auth.domain.Totp.PERIOD_SECONDS);
    }

    private Map<String, Object> termsOf(String email) {
        return jdbc.sql("""
                        SELECT terms_language, terms_english_requested_at IS NOT NULL AS english_requested
                          FROM identity.users WHERE email = CAST(:e AS citext)""").param("e", email).query().singleRow();
    }

    @Nested
    class Validation {

        @Test
        void emptyForm_everyRequiredMessage() throws Exception {
            postJson("/api/auth/register", new MockHttpSession(), "{}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors", hasSize(5)))
                    .andExpect(jsonPath("$.errors[?(@.field=='firstName')].message")
                            .value("First name is required."))
                    .andExpect(
                            jsonPath("$.errors[?(@.field=='lastName')].message").value("Last name is required."))
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].message")
                            .value("Mobile number is required for verification."))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message").value("Email is required."))
                    .andExpect(jsonPath("$.errors[?(@.field=='terms')].message")
                            .value("You need to accept the Terms and Privacy Policy."))
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].rule").value("required"));
        }

        /** S-40: the same messages in French when the browser (or the Studio's language switch) asks for it. */
        @Test
        void emptyForm_inFrench() throws Exception {
            mvc.perform(post("/api/auth/register")
                            .session(new MockHttpSession())
                            .header("Accept-Language", "fr-CA,fr;q=0.9")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='firstName')].message")
                            .value("Le prénom est obligatoire."))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message").value("Le courriel est obligatoire."))
                    .andExpect(jsonPath("$.errors[?(@.field=='terms')].message")
                            .value("Vous devez accepter les Conditions et la Politique de confidentialité."));
        }

        @Test
        void blankNames_areTrimmedAndRequired() throws Exception {
            postJson("/api/auth/register", new MockHttpSession(), """
                            {"firstName":"   ","lastName":" ","phone":"4035550148","email":"a@b.ca","terms":true}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors", hasSize(2)))
                    .andExpect(jsonPath("$.errors[0].field").value("firstName"))
                    .andExpect(jsonPath("$.errors[1].field").value("lastName"));
        }

        @Test
        void badPhoneAndEmail_formatMessages() throws Exception {
            postJson("/api/auth/register", new MockHttpSession(), """
                            {"firstName":"A","lastName":"B","phone":"555-01","email":"not-an-email","terms":true}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].message")
                            .value("Enter a valid Canadian mobile, e.g. +1 403 555 0148."))
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].rule").value("format"))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message")
                            .value("That doesn't look like an email address."))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].rule").value("format"));
        }

        @Test
        void termsNotAccepted() throws Exception {
            var p = newPerson();
            postJson("/api/auth/register", new MockHttpSession(), p.json().replace("\"terms\":true", "\"terms\":false"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("terms"))
                    .andExpect(
                            jsonPath("$.errors[0].message").value("You need to accept the Terms and Privacy Policy."));
        }

        @Test
        void emailAndPhoneMustBeUnique() throws Exception {
            var existing = register(newPerson()).person();
            var clash =
                    new Person("Jo", "Doe", existing.phone(), existing.email().toUpperCase(java.util.Locale.ROOT));
            postJson("/api/auth/register", new MockHttpSession(), clash.json())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].rule").value("unique"))
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].rule").value("unique"));
        }

        @Test
        void codeMustBeSixDigits() throws Exception {
            var session = started(newPerson());
            postJson("/api/auth/register/verify", session, json(Map.of("code", "12ab")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"))
                    .andExpect(jsonPath("$.errors[0].message").value("The code is 6 digits."));
            postJson("/api/auth/register/verify", session, "{}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Enter the 6-digit code."));
        }

        @Test
        void disallowedOrigin_isRejected() throws Exception {
            mvc.perform(post("/api/auth/register")
                            .header("Origin", "https://evil.example")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(newPerson().json()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class PhoneCode {

        @Test
        void wrongCode() throws Exception {
            var person = newPerson();
            var session = started(person);
            postJson("/api/auth/register/verify", session, json(Map.of("code", wrong(sms.lastCodeTo(person.e164())))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"))
                    .andExpect(
                            jsonPath("$.errors[0].message").value("That code doesn't match. Check it and try again."));
        }

        @Test
        void expiredCode() throws Exception {
            var person = newPerson();
            var session = started(person);
            clock.advanceSeconds(11 * 60);
            postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("That code has expired. Send a new one."));
        }

        @Test
        void fiveWrongCodes_lockTheCode() throws Exception {
            var person = newPerson();
            var session = started(person);
            var right = sms.lastCodeTo(person.e164());
            for (int i = 0; i < 4; i++) {
                postJson("/api/auth/register/verify", session, json(Map.of("code", wrong(right))))
                        .andExpect(status().isUnprocessableContent());
            }
            postJson("/api/auth/register/verify", session, json(Map.of("code", wrong(right))))
                    .andExpect(jsonPath("$.errors[0].message").value("Too many tries. Send a new code."));
            postJson("/api/auth/register/verify", session, json(Map.of("code", right)))
                    .andExpect(jsonPath("$.errors[0].message").value("Too many tries. Send a new code."));
        }

        @Test
        void resendIsThrottledFor45Seconds() throws Exception {
            var person = newPerson();
            var session = started(person);
            clock.advanceSeconds(10);
            postJson("/api/auth/register/resend", session, json(Map.of("channel", "sms")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "35"))
                    .andExpect(jsonPath("$.code").value("otp_throttled"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(35));
            clock.advanceSeconds(35);
            postJson("/api/auth/register/resend", session, json(Map.of("channel", "sms")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resendAfterSeconds").value(45));
            assertThat(sms.sentTo(person.e164())).hasSize(2);
        }

        @Test
        void resubmittingTheForm_insideTheCooldown_doesNotSendAnotherCode() throws Exception {
            var person = newPerson();
            var session = started(person);
            postJson("/api/auth/register", session, person.json())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resendAfterSeconds").value(45));
            assertThat(sms.sentTo(person.e164())).hasSize(1);
        }

        @Test
        void voiceFallback_isAvailableRightAway_andTheNewCodeWorks() throws Exception {
            var person = newPerson();
            var session = started(person);
            var smsCode = sms.lastCodeTo(person.e164());
            postJson("/api/auth/register/resend", session, json(Map.of("channel", "voice")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.channel").value("voice"));
            var sent = sms.sentTo(person.e164());
            assertThat(sent.getLast().channel()).isEqualTo(Channel.VOICE);
            // a second call inside the cool-down is throttled
            postJson("/api/auth/register/resend", session, json(Map.of("channel", "voice")))
                    .andExpect(status().isTooManyRequests());
            if (!smsCode.equals(sent.getLast().code())) {
                postJson("/api/auth/register/verify", session, json(Map.of("code", smsCode)))
                        .andExpect(status().isUnprocessableContent());
            }
            postJson(
                            "/api/auth/register/verify",
                            session,
                            json(Map.of("code", sent.getLast().code())))
                    .andExpect(status().isOk());
        }

        @Test
        void stepsOutOfOrder_areRejected() throws Exception {
            mvc.perform(post("/api/auth/register/totp").session(new MockHttpSession()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("flow_not_started"));
            var session = started(newPerson());
            mvc.perform(post("/api/auth/register/totp").session(session)).andExpect(status().isConflict());
        }

        @Test
        void wrongAuthenticatorCode_keepsTheRegistrationOpen() throws Exception {
            var person = newPerson();
            var session = started(person);
            postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/auth/register/totp").session(session)).andExpect(status().isOk());
            postJson("/api/auth/register/totp/verify", session, json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
            assertThat(userCount(person.email())).isZero();
            mvc.perform(get("/api/auth/session").session(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$").doesNotExist());
        }
    }

    private MockHttpSession started(Person person) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
        return session;
    }

    private long userCount(String email) {
        return jdbc.sql("SELECT count(*) FROM identity.users WHERE email = CAST(:e AS citext)")
                .param("e", email)
                .query(Long.class)
                .single();
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    /** S-8: what the person sees when the SMS/voice provider doesn't take the code. */
    @Nested
    class Delivery {

        @Test
        void numberTheProviderRefuses_isAFieldErrorOnTheMobile_andNothingIsStarted() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            sms.failNext(SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER);

            postJson("/api/auth/register", session, person.json())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"))
                    .andExpect(jsonPath("$.errors[0].rule").value("format"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PHONE_FORMAT));
            postJson("/api/auth/register/verify", session, json(Map.of("code", "123456")))
                    .andExpect(status().isConflict());
        }

        @Test
        void providerDown_is503CodeNotSent_andTheFormCanBeSentAgain() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            sms.failNext(SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE);

            postJson("/api/auth/register", session, person.json())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("code_not_sent"))
                    .andExpect(jsonPath("$.detail").value(AuthMessages.CODE_NOT_SENT_FORM));
            postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
            assertThat(sms.sentTo(person.e164())).hasSize(1);
        }

        @Test
        void failedVoiceCall_keepsTheSmsCodeWorking() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
            var smsCode = sms.lastCodeTo(person.e164());
            sms.failNext(SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER);

            postJson("/api/auth/register/resend", session, json(Map.of("channel", "voice")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("code_not_sent"))
                    .andExpect(jsonPath("$.detail").value(AuthMessages.CALL_NOT_PLACED));
            postJson("/api/auth/register/verify", session, json(Map.of("code", smsCode)))
                    .andExpect(status().isOk());
        }

        @Test
        void codesAreWordedInTheRequestLanguage() throws Exception {
            var person = newPerson();
            mvc.perform(post("/api/auth/register")
                            .session(new MockHttpSession())
                            .header("Accept-Language", "fr-CA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(person.json()))
                    .andExpect(status().isOk());

            assertThat(sms.sentTo(person.e164()).getLast().locale().getLanguage())
                    .isEqualTo("fr");
        }
    }
}
