package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-154: Create account with email + password → confirm the email (code or link) → enrol a second factor (backup codes
 * shown once) → sign in with email + password + factor → forgot password → reset; plus the security rules: no account
 * enumeration, expired / used / locked codes, lockouts, validation messages, 401/403/409s.
 */
class AccountSignUpApiTest extends AuthIntegrationTest {

    static final String PASSWORD = "correct horse battery staple";
    static final String OTHER_PASSWORD = "Maple syrup on fresh snow 42";

    record Account(String userId, String email, String password, String totpSecret, List<String> backupCodes) {}

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String email() {
        return "kofi." + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@example.ca";
    }

    private static String form(String email, String password, String app) {
        return """
                {"firstName":"Kofi","lastName":"Mensah","email":"%s","password":"%s","passwordConfirm":"%s",
                 "terms":true,"app":"%s"}""".formatted(email, password, password, app);
    }

    /** Create account → code from the email → signed in with the password alone. */
    private MockHttpSession created(String email) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio")).andExpect(status().isAccepted());
        postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.needsSecondFactor").value(true));
        return session;
    }

    /** The whole sign-up with an authenticator app; the session ends signed in with acr=mfa. */
    private Account enrolled(String email, MockHttpSession session) throws Exception {
        var setup = mvc.perform(post("/api/auth/enroll/totp").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        var done = postJson("/api/auth/enroll/totp/verify", session, json(Map.of("code", totpNow(secret))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        List<String> codes = JsonPath.read(done, "$.backupCodes");
        String userId = JsonPath.read(done, "$.session.user.id");
        return new Account(userId, email, PASSWORD, secret, codes);
    }

    private Account account() throws Exception {
        var email = email();
        return enrolled(email, created(email));
    }

    private ResultActions signIn(MockHttpSession session, String email, String password) throws Exception {
        return postJson("/api/auth/sign-in/password", session, json(Map.of("email", email, "password", password)));
    }

    @Test
    void wholeJourney_registerVerifyEnrolSignInForgotReset() throws Exception {
        var email = email();
        var session = new MockHttpSession();

        postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.step").value("verify-email"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.resendAfterSeconds").value(60));
        // Pending until the email is confirmed: no identity.users row yet.
        assertThat(users(email)).isZero();
        var sent = emails.last(email);
        assertThat(sent.tag()).isEqualTo("account-verification");
        assertThat(sent.text()).contains("http://localhost:3100/verify-email?token=");
        assertThat(sent.subject()).contains(emails.codeTo(email));

        postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.acr").value(nullValue()))
                .andExpect(jsonPath("$.needsSecondFactor").value(true));
        var row = jdbc.sql("""
                        SELECT u.id, u.email_verified_at IS NOT NULL AS verified, u.mfa_primary, p.hash
                          FROM identity.users u JOIN auth.passwords p ON p.user_id = u.id
                         WHERE u.email = CAST(:e AS citext)""").param("e", email).query().singleRow();
        assertThat(row.get("verified")).isEqualTo(true);
        assertThat(row.get("mfa_primary")).isNull();
        assertThat((String) row.get("hash")).startsWith("{argon2}$argon2id$").doesNotContain(PASSWORD);
        // A business app can't get a code yet: the session holds the password alone.
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").value(nullValue()))
                .andExpect(jsonPath("$.needsSecondFactor").value(true));

        var setup = mvc.perform(post("/api/auth/enroll/totp").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/Northline")))
                .andExpect(jsonPath("$.qrCode").value(startsWith("data:image/png;base64,")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String otpauth = JsonPath.read(setup, "$.otpauthUri");
        assertThat(otpauth).contains("issuer=Northline").contains(email.replace("@", "%40"));
        String secret = JsonPath.read(setup, "$.secret");
        var enrolled = postJson("/api/auth/enroll/totp/verify", session, json(Map.of("code", totpNow(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.acr").value("mfa"))
                .andExpect(jsonPath("$.backupCodes", hasSize(10)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> backupCodes = JsonPath.read(enrolled, "$.backupCodes");
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(jsonPath("$.acr").value("mfa"))
                .andExpect(jsonPath("$.needsSecondFactor").value(false));
        // Enrolling again is refused: the account has a factor now (Settings › Security changes it, with step-up).
        mvc.perform(post("/api/auth/enroll/totp").session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_enrolled"));

        // Sign in elsewhere: email + password → the account's own factors → authenticator.
        var other = new MockHttpSession();
        signIn(other, email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("factor"))
                .andExpect(jsonPath("$.factors[0]").value("totp"))
                .andExpect(jsonPath("$.factors[1]").value("backup_code"))
                .andExpect(jsonPath("$.factors", hasSize(2)));
        postJson("/api/auth/sign-in/totp", other, json(Map.of("code", totpNow(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").value("mfa"));
        assertThat(sessionsOpen(String.valueOf(row.get("id")))).isEqualTo(2);

        // Forgot password → code by email → new password: every session ends; the factor is still asked.
        var signedOut = new MockHttpSession();
        postJson("/api/auth/password/forgot", signedOut, json(Map.of("email", email, "app", "studio")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAfterSeconds").value(60));
        assertThat(emails.last(email).tag()).isEqualTo("password-reset");
        postJson(
                        "/api/auth/password/reset",
                        signedOut,
                        json(Map.of("code", emails.codeTo(email), "password", OTHER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        assertThat(sessionsOpen(String.valueOf(row.get("id")))).isZero();
        signIn(new MockHttpSession(), email, PASSWORD).andExpect(status().isUnprocessableContent());
        var again = new MockHttpSession();
        signIn(again, email, OTHER_PASSWORD).andExpect(jsonPath("$.step").value("factor"));
        postJson("/api/auth/sign-in/backup-code", again, json(Map.of("code", backupCodes.getFirst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").value("mfa"));
    }

    @Test
    void verificationLink_confirmsAnywhere_butSignsInOnlyTheBrowserThatSentTheForm() throws Exception {
        var email = email();
        var session = new MockHttpSession();
        postJson("/api/auth/accounts", session, form(email, PASSWORD, "consumer"))
                .andExpect(status().isAccepted());
        var token = emails.tokenTo(email);
        assertThat(emails.last(email).text()).contains("http://localhost:3000/verify-email?token=");

        postJson("/api/auth/accounts/verify-link", new MockHttpSession(), json(Map.of("token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.signedIn").value(false))
                .andExpect(jsonPath("$.session").value(nullValue()));
        assertThat(users(email)).isOne();
        // Single use.
        postJson("/api/auth/accounts/verify-link", new MockHttpSession(), json(Map.of("token", token)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("token"))
                .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.LINK_EXPIRED));
        // That browser now signs in with the password: no factor yet → signed in, enrolment next.
        signIn(new MockHttpSession(), email, PASSWORD)
                .andExpect(jsonPath("$.step").value("enroll"))
                .andExpect(jsonPath("$.session.needsSecondFactor").value(true))
                .andExpect(jsonPath("$.session.acr").value(nullValue()));
    }

    @Test
    void theSameBrowserOpeningTheLink_isSignedIn() throws Exception {
        var email = email();
        var session = new MockHttpSession();
        postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio")).andExpect(status().isAccepted());
        postJson("/api/auth/accounts/verify-link", session, json(Map.of("token", emails.tokenTo(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedIn").value(true))
                .andExpect(jsonPath("$.session.needsSecondFactor").value(true));
    }

    @Nested
    class NoEnumeration {

        @Test
        void anAddressWithAnAccount_getsTheSameAnswer_andAnEmailInsteadOfACode() throws Exception {
            var existing = account();
            var session = new MockHttpSession();
            var before = emails.to(existing.email(), 0).size();
            postJson("/api/auth/accounts", session, form(existing.email(), OTHER_PASSWORD, "studio"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.step").value("verify-email"))
                    .andExpect(jsonPath("$.resendAfterSeconds").value(60));
            var mail = emails.to(existing.email(), before + 1);
            assertThat(mail.getLast().tag()).isEqualTo("account-exists");
            assertThat(mail.getLast().text())
                    .contains("/forgot-password")
                    .doesNotContainPattern("(?m)^\\s*\\d{6}\\s*$");
            assertThat(jdbc.sql("SELECT count(*) FROM auth.pending_accounts WHERE email = CAST(:e AS citext)")
                            .param("e", existing.email())
                            .query(Integer.class)
                            .single())
                    .isZero();
            // Any code is "wrong", as for a real pending account.
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", "123456")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("mismatch"));
            // The old password still works; nothing changed.
            signIn(new MockHttpSession(), existing.email(), PASSWORD)
                    .andExpect(jsonPath("$.step").value("factor"));
        }

        @Test
        void wrongPassword_andUnknownEmail_getTheSameAnswer() throws Exception {
            var existing = account();
            var wrong = signIn(new MockHttpSession(), existing.email(), "not the password at all")
                    .andExpect(status().isUnprocessableContent())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var unknown = signIn(new MockHttpSession(), email(), "not the password at all")
                    .andExpect(status().isUnprocessableContent())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(unknown).isEqualTo(wrong);
            assertThat(wrong).contains("\"field\":\"password\"", AuthMessages.CREDENTIALS_WRONG);
        }

        @Test
        void forgotPassword_forAnUnknownAddress_looksTheSame_andSendsNothing() throws Exception {
            var nobody = email();
            var session = new MockHttpSession();
            postJson("/api/auth/password/forgot", session, json(Map.of("email", nobody)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.step").value("verify-email"))
                    .andExpect(jsonPath("$.resendAfterSeconds").value(60));
            assertThat(emails.to(nobody, 1)).isEmpty();
            postJson("/api/auth/password/reset", session, json(Map.of("code", "123456", "password", OTHER_PASSWORD)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"))
                    .andExpect(jsonPath("$.errors[0].rule").value("mismatch"));
        }
    }

    @Nested
    class Codes {

        @Test
        void anExpiredCode_isRefused_andANewOneWorks() throws Exception {
            var email = email();
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio"));
            var code = emails.codeTo(email);
            clock.advanceSeconds(16 * 60);
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", code)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("expired"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CODE_EXPIRED));
            var before = emails.to(email, 1).size();
            mvc.perform(post("/api/auth/accounts/resend").session(session)).andExpect(status().isAccepted());
            emails.to(email, before + 1);
            // The old code stopped working when the new one was sent.
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", code)))
                    .andExpect(status().isUnprocessableContent());
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                    .andExpect(status().isCreated());
        }

        @Test
        void resendInsideTheCoolDown_isThrottled() throws Exception {
            var email = email();
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio"));
            mvc.perform(post("/api/auth/accounts/resend").session(session))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("otp_throttled"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(60));
            clock.advanceSeconds(61);
            mvc.perform(post("/api/auth/accounts/resend").session(session)).andExpect(status().isAccepted());
        }

        @Test
        void fiveWrongCodes_lockTheCode() throws Exception {
            var email = email();
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio"));
            var right = emails.codeTo(email);
            var wrong = right.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 4; i++) {
                postJson("/api/auth/accounts/verify", session, json(Map.of("code", wrong)))
                        .andExpect(jsonPath("$.errors[0].rule").value("mismatch"));
            }
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", wrong)))
                    .andExpect(jsonPath("$.errors[0].rule").value("locked"));
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", right)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CODE_LOCKED));
        }

        @Test
        void aUsedResetCode_doesNotWorkTwice() throws Exception {
            var acc = account();
            var session = new MockHttpSession();
            postJson("/api/auth/password/forgot", session, json(Map.of("email", acc.email())));
            var code = emails.codeTo(acc.email());
            postJson("/api/auth/password/reset", session, json(Map.of("code", code, "password", OTHER_PASSWORD)))
                    .andExpect(status().isOk());
            postJson("/api/auth/password/reset", session, json(Map.of("code", code, "password", PASSWORD)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("flow_not_started"));
        }

        @Test
        void resetByLink_inAnotherBrowser() throws Exception {
            var acc = account();
            postJson("/api/auth/password/forgot", new MockHttpSession(), json(Map.of("email", acc.email())));
            var token = emails.tokenTo(acc.email());
            postJson(
                            "/api/auth/password/reset",
                            new MockHttpSession(),
                            json(Map.of("token", token, "password", OTHER_PASSWORD)))
                    .andExpect(status().isOk());
            signIn(new MockHttpSession(), acc.email(), OTHER_PASSWORD)
                    .andExpect(jsonPath("$.step").value("factor"));
            postJson(
                            "/api/auth/password/reset",
                            new MockHttpSession(),
                            json(Map.of("token", token, "password", PASSWORD)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("token"));
        }
    }

    @Nested
    class Validation {

        private ResultActions create(String body) throws Exception {
            return postJson("/api/auth/accounts", new MockHttpSession(), body);
        }

        @Test
        void requiredFields() throws Exception {
            create("{\"terms\":false}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='firstName')].message")
                            .value(AuthMessages.FIRST_NAME_REQUIRED))
                    .andExpect(
                            jsonPath("$.errors[?(@.field=='lastName')].message").value(AuthMessages.LAST_NAME_REQUIRED))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message").value(AuthMessages.EMAIL_REQUIRED))
                    .andExpect(
                            jsonPath("$.errors[?(@.field=='password')].message").value(AuthMessages.PASSWORD_REQUIRED))
                    .andExpect(jsonPath("$.errors[?(@.field=='terms')].message").value(AuthMessages.TERMS_REQUIRED));
        }

        @Test
        void passwordRules() throws Exception {
            var email = email();
            create(form(email, "short pass", "studio"))
                    .andExpect(jsonPath("$.errors[0].field").value("password"))
                    .andExpect(jsonPath("$.errors[0].rule").value("length"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PASSWORD_TOO_SHORT));
            create(form(email, "x".repeat(129), "studio"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PASSWORD_TOO_LONG));
            create(form(email, "123456789012", "studio"))
                    .andExpect(jsonPath("$.errors[0].rule").value("common"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PASSWORD_COMMON));
            create(form(email, "aaaaaaaaaaaaaaaa", "studio"))
                    .andExpect(jsonPath("$.errors[0].rule").value("common"));
            create(form(email, email.toUpperCase(java.util.Locale.ROOT), "studio"))
                    .andExpect(jsonPath("$.errors[0].rule").value("email"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PASSWORD_IS_EMAIL));
            create("""
                            {"firstName":"Kofi","lastName":"Mensah","email":"%s","password":"%s","passwordConfirm":"other",
                             "terms":true}""".formatted(email, PASSWORD))
                    .andExpect(jsonPath("$.errors[0].field").value("passwordConfirm"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.PASSWORD_CONFIRM));
            // Nothing was stored or sent for any of them.
            assertThat(emails.to(email, 0)).isEmpty();
        }

        @Test
        void formatsAndApp() throws Exception {
            create("""
                            {"firstName":"Kofi","lastName":"Mensah","email":"kofi@","password":"%s","phone":"12",
                             "terms":true,"app":"mobile"}""".formatted(PASSWORD))
                    .andExpect(jsonPath("$.errors[?(@.field=='email')].message").value(AuthMessages.EMAIL_FORMAT))
                    .andExpect(jsonPath("$.errors[?(@.field=='phone')].message").value(AuthMessages.PHONE_FORMAT))
                    .andExpect(jsonPath("$.errors[?(@.field=='app')].message").value(AuthMessages.APP_UNKNOWN));
        }

        @Test
        void frenchMessages() throws Exception {
            mvc.perform(post("/api/auth/accounts")
                            .header("Accept-Language", "fr-CA")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(form(email(), "short pass", "studio")))
                    .andExpect(jsonPath("$.errors[0].message").value("Utilisez au moins 12 caractères."));
        }

        @Test
        void theOptionalMobile_isStoredUnconfirmed() throws Exception {
            var email = email();
            var n = ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
            var phone = "+1 (825) %03d-%04d".formatted(n / 10_000 % 1000, n % 10_000);
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, """
                            {"firstName":"Kofi","lastName":"Mensah","email":"%s","password":"%s","phone":"%s",
                             "terms":true}""".formatted(email, PASSWORD, phone))
                    .andExpect(status().isAccepted());
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                    .andExpect(status().isCreated());
            var row = jdbc.sql("""
                            SELECT phone, phone_verified_at IS NULL AS unconfirmed FROM identity.users
                             WHERE email = CAST(:e AS citext)""").param("e", email).query().singleRow();
            assertThat(row.get("phone")).asString().startsWith("+1825");
            assertThat(row.get("unconfirmed")).isEqualTo(true);
        }
    }

    @Nested
    class Rules {

        @Test
        void aPendingAccount_signingIn_getsANewCode() throws Exception {
            var email = email();
            postJson("/api/auth/accounts", new MockHttpSession(), form(email, PASSWORD, "studio"));
            emails.to(email, 1);
            clock.advanceSeconds(61);
            var session = new MockHttpSession();
            signIn(session, email, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("verify-email"))
                    .andExpect(jsonPath("$.email").value(email));
            var code = emails.to(email, 2).getLast().text();
            assertThat(code).contains("verify-email?token=");
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                    .andExpect(status().isCreated());
        }

        @Test
        void registeringTheSameAddressAgain_replacesThePendingAccount() throws Exception {
            var email = email();
            postJson("/api/auth/accounts", new MockHttpSession(), form(email, PASSWORD, "studio"));
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, form(email, OTHER_PASSWORD, "studio"));
            emails.to(email, 2);
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))))
                    .andExpect(status().isCreated());
            signIn(new MockHttpSession(), email, PASSWORD).andExpect(status().isUnprocessableContent());
            signIn(new MockHttpSession(), email, OTHER_PASSWORD)
                    .andExpect(jsonPath("$.step").value("enroll"));
        }

        @Test
        void identifierFirst_cannotSkipThePassword() throws Exception {
            var acc = account();
            var session = new MockHttpSession();
            postJson("/api/auth/sign-in", session, json(Map.of("identifier", acc.email())))
                    .andExpect(status().isOk());
            postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(acc.totpSecret()))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("mismatch"));
            postJson(
                            "/api/auth/sign-in/backup-code",
                            session,
                            json(Map.of("code", acc.backupCodes().getFirst())))
                    .andExpect(status().isUnprocessableContent());
            // The backup code wasn't used up by the refused attempt.
            var ok = new MockHttpSession();
            signIn(ok, acc.email(), PASSWORD);
            postJson(
                            "/api/auth/sign-in/backup-code",
                            ok,
                            json(Map.of("code", acc.backupCodes().getFirst())))
                    .andExpect(status().isOk());
        }

        @Test
        void tenWrongPasswords_lockTheAccount_forEveryone() throws Exception {
            var acc = account();
            for (int i = 0; i < 9; i++) {
                signIn(new MockHttpSession(), acc.email(), "wrong password number " + i)
                        .andExpect(status().isUnprocessableContent());
            }
            signIn(new MockHttpSession(), acc.email(), "wrong password number 9")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("rate_limited"));
            // Even the right password waits now (another browser, same account).
            signIn(new MockHttpSession(), acc.email(), PASSWORD).andExpect(status().isTooManyRequests());
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM developer.audit_log
                             WHERE actor_id = :u AND action = 'auth.rate_limited'""")
                            .param("u", acc.userId())
                            .query(Integer.class)
                            .single())
                    .isPositive();
            // Audit rows carry no email or password.
            assertThat(jdbc.sql("SELECT string_agg(after::text, ' ') FROM developer.audit_log WHERE actor_id = :u")
                            .param("u", acc.userId())
                            .query(String.class)
                            .single())
                    .doesNotContain(acc.email())
                    .doesNotContain(PASSWORD);
        }

        @Test
        void enrolment_needsASignedInSession() throws Exception {
            mvc.perform(post("/api/auth/enroll/totp").session(new MockHttpSession()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthenticated"));
        }

        @Test
        void sessionIdChanges_atSignIn_andAtEnrolment() throws Exception {
            var email = email();
            var session = new MockHttpSession();
            postJson("/api/auth/accounts", session, form(email, PASSWORD, "studio"));
            var before = session.getId();
            postJson("/api/auth/accounts/verify", session, json(Map.of("code", emails.codeTo(email))));
            assertThat(session.getId()).isNotEqualTo(before);
            var signedIn = session.getId();
            enrolled(email, session);
            assertThat(session.getId()).isNotEqualTo(signedIn);
        }
    }

    @Nested
    class Settings {

        @Test
        void changePassword_needsTheCurrentOne_andAStepUp_andEndsOtherSessions() throws Exception {
            var email = email();
            var session = created(email);
            var acc = enrolled(email, session);
            var other = new MockHttpSession();
            signIn(other, email, PASSWORD);
            postJson("/api/auth/sign-in/totp", other, json(Map.of("code", totpNow(acc.totpSecret()))))
                    .andExpect(status().isOk());
            clock.advanceSeconds(Totp.PERIOD_SECONDS);

            postJson("/api/auth/security/password", session, json(Map.of("password", OTHER_PASSWORD)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CURRENT_PASSWORD_REQUIRED));
            postJson(
                            "/api/auth/security/password",
                            session,
                            json(Map.of("currentPassword", "not it at all, sorry", "password", OTHER_PASSWORD)))
                    .andExpect(jsonPath("$.errors[0].message").value(AuthMessages.CURRENT_PASSWORD_WRONG));
            postJson(
                            "/api/auth/security/password",
                            session,
                            json(Map.of("currentPassword", PASSWORD, "password", "password1234")))
                    .andExpect(jsonPath("$.errors[0].rule").value("common"));
            postJson(
                            "/api/auth/security/password",
                            session,
                            json(Map.of("currentPassword", PASSWORD, "password", OTHER_PASSWORD)))
                    .andExpect(status().isNoContent());
            assertThat(sessionsOpen(acc.userId())).isOne();
            signIn(new MockHttpSession(), email, OTHER_PASSWORD)
                    .andExpect(jsonPath("$.step").value("factor"));

            clock.advanceSeconds(11 * 60); // the second factor is no longer recent
            postJson(
                            "/api/auth/security/password",
                            session,
                            json(Map.of("currentPassword", OTHER_PASSWORD, "password", PASSWORD)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            mvc.perform(post("/api/auth/backup-codes").session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
        }

        @Test
        void replaceTheAuthenticator_theOldSecretStopsWorking() throws Exception {
            var email = email();
            var session = created(email);
            var acc = enrolled(email, session);
            var setup = mvc.perform(post("/api/auth/security/authenticator").session(session))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String secret = JsonPath.read(setup, "$.secret");
            postJson("/api/auth/security/authenticator/verify", session, json(Map.of("code", totpNow(secret))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticator").value(true));
            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            var old = new MockHttpSession();
            signIn(old, email, PASSWORD);
            postJson("/api/auth/sign-in/totp", old, json(Map.of("code", totpNow(acc.totpSecret()))))
                    .andExpect(status().isUnprocessableContent());
            var fresh = new MockHttpSession();
            signIn(fresh, email, PASSWORD);
            postJson("/api/auth/sign-in/totp", fresh, json(Map.of("code", totpNow(secret))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/auth/security").session(session))
                    .andExpect(jsonPath("$.password").value(true))
                    .andExpect(jsonPath("$.authenticator").value(true));
        }

        @Test
        void regenerateBackupCodes_withARecentFactor() throws Exception {
            var email = email();
            var session = created(email);
            var acc = enrolled(email, session);
            var fresh = mvc.perform(post("/api/auth/backup-codes").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.codes", hasSize(10)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<String> codes = JsonPath.read(fresh, "$.codes");
            assertThat(codes).doesNotContainAnyElementsOf(acc.backupCodes());
        }
    }

    @Nested
    class OlderAccounts {

        /** The owner's state: an account from before S-154 with no password and no second factor. */
        @Test
        void noPasswordNoFactor_resetsByEmail_thenEnrols() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))));
            mvc.perform(post("/api/auth/register/complete").session(session)).andExpect(status().isCreated());

            signIn(new MockHttpSession(), person.email(), PASSWORD).andExpect(status().isUnprocessableContent());
            var browser = new MockHttpSession();
            postJson("/api/auth/password/forgot", browser, json(Map.of("email", person.email())))
                    .andExpect(status().isAccepted());
            postJson(
                            "/api/auth/password/reset",
                            browser,
                            json(Map.of("code", emails.codeTo(person.email()), "password", PASSWORD)))
                    .andExpect(status().isOk());
            var signIn = new MockHttpSession();
            signIn(signIn, person.email(), PASSWORD)
                    .andExpect(jsonPath("$.step").value("enroll"))
                    .andExpect(jsonPath("$.session.needsSecondFactor").value(true));
            enrolled(person.email(), signIn);
            mvc.perform(get("/api/auth/session").session(signIn))
                    .andExpect(jsonPath("$.acr").value("mfa"));
        }
    }

    private int users(String email) {
        return jdbc.sql("SELECT count(*) FROM identity.users WHERE email = CAST(:e AS citext)")
                .param("e", email)
                .query(Integer.class)
                .single();
    }

    private int sessionsOpen(String userId) {
        return jdbc.sql("SELECT count(*) FROM identity.sessions WHERE user_id = :u AND revoked_at IS NULL")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }
}
