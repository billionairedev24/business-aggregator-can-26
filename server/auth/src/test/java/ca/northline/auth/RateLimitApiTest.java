package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SharedValkey;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-9 rate limits through the API, counted in Valkey (Testcontainers {@code valkey/valkey:8}): per account (what was
 * typed), per IP and per auth session, across sessions, with 429 + {@code Retry-After}, lockout audit, reset on success,
 * and the client IP taken from {@code X-Forwarded-For} only when a trusted proxy sent it. Limits are lowered here so each
 * one trips after a few calls; every test uses its own people, IPs and sessions (Valkey is shared, never flushed).
 */
@TestPropertySource(
        properties = {
            "northline.auth.rate-limits.store=redis",
            "northline.auth.trusted-proxies=10.0.0.0/8",
            "northline.auth.rate-limits.limits.otp-send.account.max=3",
            "northline.auth.rate-limits.limits.otp-send.ip.max=5",
            "northline.auth.rate-limits.limits.otp-send.session.max=4",
            "northline.auth.rate-limits.limits.otp-verify.account.max=3",
            "northline.auth.rate-limits.limits.otp-verify.ip.max=50",
            "northline.auth.rate-limits.limits.sign-in-lookup.ip.max=4",
            "northline.auth.rate-limits.limits.sign-in-lookup.ip.lockout=2s",
            "northline.auth.rate-limits.limits.sign-in-lookup.session.max=6",
            "northline.auth.rate-limits.limits.totp-verify.account.max=3",
            "northline.auth.rate-limits.limits.totp-verify.account.lockout=2s",
            "northline.auth.rate-limits.limits.totp-verify.account.max-lockout=8s",
            "northline.auth.rate-limits.limits.totp-verify.ip.max=50",
            "northline.auth.rate-limits.limits.backup-code-verify.account.max=3",
            "northline.auth.rate-limits.limits.backup-code-verify.ip.max=50",
            "northline.auth.rate-limits.limits.passkey-assertion.session.max=3",
            "northline.auth.rate-limits.limits.passkey-assertion.ip.max=50",
            "northline.auth.rate-limits.limits.step-up.account.max=3",
            "northline.auth.rate-limits.limits.step-up.ip.max=50",
            "northline.auth.rate-limits.limits.security-change.account.max=3",
            "northline.auth.rate-limits.limits.security-change.ip.max=50",
            "northline.auth.client-city-header=X-Client-City",
        })
class RateLimitApiTest extends AuthIntegrationTest {

    private static final String RATE_LIMITED = "Too many attempts. Wait a moment and try again.";

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", SharedValkey::host);
        registry.add("spring.data.redis.port", SharedValkey::port);
    }

    private final String registrationIp = newIp();

    /** Registrations of this test instance come from their own IP, so they never trip the per-IP limits. */
    @Override
    protected MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        return from(request, registrationIp);
    }

    /** A documentation-range address nobody else in the suite uses. */
    private static String newIp() {
        var r = ThreadLocalRandom.current();
        return "198.51.%d.%d".formatted(r.nextInt(0, 256), r.nextInt(1, 255));
    }

    private ResultActions postJson(String path, MockHttpSession session, String ip, String body) throws Exception {
        return mvc.perform(from(post(path), ip)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private static ResultMatcher rateLimited() {
        return result -> {
            status().isTooManyRequests().match(result);
            jsonPath("$.code").value("rate_limited").match(result);
            jsonPath("$.detail").value(RATE_LIMITED).match(result);
            jsonPath("$.retryAfterSeconds").isNumber().match(result);
            header().exists("Retry-After").match(result);
        };
    }

    private static int retryAfter(ResultActions actions) {
        return Integer.parseInt(actions.andReturn().getResponse().getHeader("Retry-After"));
    }

    private long auditRows(String userId) {
        return jdbc.sql("SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = 'auth.rate_limited'")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    @Nested
    class PhoneCodes {

        @Test
        void sendingIsLimitedPerMobile_acrossSessionsAndIps() throws Exception {
            var person = newPerson();
            for (int i = 0; i < 3; i++) {
                postJson("/api/auth/register", new MockHttpSession(), newIp(), person.json())
                        .andExpect(status().isOk());
            }
            var denied = postJson("/api/auth/register", new MockHttpSession(), newIp(), person.json())
                    .andExpect(rateLimited());
            assertThat(retryAfter(denied)).isBetween(3000, 3600); // otp-send lockout: 1 h
            assertThat(sms.sentTo(person.e164())).hasSize(3);
        }

        @Test
        void sendingIsLimitedPerIp() throws Exception {
            var ip = newIp();
            for (int i = 0; i < 5; i++) {
                postJson(
                                "/api/auth/register",
                                new MockHttpSession(),
                                ip,
                                newPerson().json())
                        .andExpect(status().isOk());
            }
            var sixth = newPerson();
            postJson("/api/auth/register", new MockHttpSession(), ip, sixth.json())
                    .andExpect(rateLimited());
            assertThat(sms.sentTo(sixth.e164())).isEmpty();
            postJson("/api/auth/register", new MockHttpSession(), newIp(), sixth.json())
                    .andExpect(status().isOk());
        }

        @Test
        void resendAndVoiceCallsCount() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            var ip = newIp();
            postJson("/api/auth/register", session, ip, person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/resend", session, ip, json(Map.of("channel", "voice")))
                    .andExpect(status().isOk());
            clock.advanceSeconds(46);
            postJson("/api/auth/register/resend", session, ip, json(Map.of("channel", "sms")))
                    .andExpect(status().isOk());
            clock.advanceSeconds(46);
            postJson("/api/auth/register/resend", session, ip, json(Map.of("channel", "voice")))
                    .andExpect(rateLimited());
            assertThat(sms.sentTo(person.e164())).hasSize(3);
        }

        @Test
        void wrongCodes_countPerMobile_acrossSessions_evenWithANewCode() throws Exception {
            var person = newPerson();
            var first = new MockHttpSession();
            postJson("/api/auth/register", first, newIp(), person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/verify", first, newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());
            postJson("/api/auth/register/verify", first, newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());

            var second = new MockHttpSession(); // a new flow and a new code…
            postJson("/api/auth/register", second, newIp(), person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/verify", second, newIp(), json(Map.of("code", "000000")))
                    .andExpect(rateLimited()); // …but the third wrong code for this mobile locks it
            postJson("/api/auth/register/verify", second, newIp(), json(Map.of("code", sms.lastCodeTo(person.e164()))))
                    .andExpect(rateLimited()); // even the right code waits
        }
    }

    @Nested
    class SignIn {

        @Test
        void identifierLookups_areLimitedPerIp_notPerAccount() throws Exception {
            var ip = newIp();
            for (int i = 0; i < 4; i++) {
                postJson(
                                "/api/auth/sign-in",
                                new MockHttpSession(),
                                ip,
                                json(Map.of("identifier", "x" + i + "@example.ca")))
                        .andExpect(status().isOk());
            }
            postJson("/api/auth/sign-in", new MockHttpSession(), ip, json(Map.of("identifier", "y@example.ca")))
                    .andExpect(rateLimited());
            // The same identifier from elsewhere is fine: typing someone's email never locks them out.
            postJson("/api/auth/sign-in", new MockHttpSession(), newIp(), json(Map.of("identifier", "x0@example.ca")))
                    .andExpect(status().isOk());
        }

        @Test
        void identifierLookups_areLimitedPerSession() throws Exception {
            var session = new MockHttpSession();
            for (int i = 0; i < 6; i++) {
                postJson("/api/auth/sign-in", session, newIp(), json(Map.of("identifier", "s" + i + "@example.ca")))
                        .andExpect(status().isOk());
            }
            postJson("/api/auth/sign-in", session, newIp(), json(Map.of("identifier", "s@example.ca")))
                    .andExpect(rateLimited());
        }

        private MockHttpSession started(String identifier, String ip) throws Exception {
            var session = new MockHttpSession();
            postJson("/api/auth/sign-in", session, ip, json(Map.of("identifier", identifier)))
                    .andExpect(status().isOk());
            return session;
        }

        @Test
        void wrongTotpCodes_lockTheAccountAcrossSessions_withBackoff_andAreAudited() throws Exception {
            var user = register(newPerson());
            var email = user.person().email();
            postJson("/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());
            postJson("/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());
            // Third failure, from a third session and IP, by mobile instead of email: same account.
            var third = postJson(
                            "/api/auth/sign-in/totp",
                            started(user.e164Digits(), newIp()),
                            newIp(),
                            json(Map.of("code", "000000")))
                    .andExpect(rateLimited());
            assertThat(retryAfter(third)).isEqualTo(2);
            assertThat(auditRows(user.userId())).isEqualTo(1);

            // Locked: even the right code is refused, before it is checked.
            postJson(
                            "/api/auth/sign-in/totp",
                            started(email, newIp()),
                            newIp(),
                            json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(rateLimited());

            Thread.sleep(2100);
            for (int i = 0; i < 2; i++) {
                postJson("/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                        .andExpect(status().isUnprocessableContent());
            }
            var again = postJson(
                            "/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                    .andExpect(rateLimited());
            assertThat(retryAfter(again)).isEqualTo(4); // second lockout: doubled
            assertThat(auditRows(user.userId())).isEqualTo(2);
        }

        @Test
        void aSuccessResetsTheAccountCounter() throws Exception {
            var user = register(newPerson());
            var email = user.person().email();
            for (int i = 0; i < 2; i++) {
                postJson("/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                        .andExpect(status().isUnprocessableContent());
            }
            postJson(
                            "/api/auth/sign-in/totp",
                            started(email, newIp()),
                            newIp(),
                            json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
            for (int i = 0; i < 2; i++) {
                postJson("/api/auth/sign-in/totp", started(email, newIp()), newIp(), json(Map.of("code", "000000")))
                        .andExpect(status().isUnprocessableContent()); // 1 and 2 again, not 3 and 4
            }
        }

        @Test
        void unknownAccounts_areLimitedExactlyLikeKnownOnes() throws Exception {
            var known = register(newPerson()).person().email();
            var unknown = "nobody." + ThreadLocalRandom.current().nextInt(1_000_000) + "@example.ca";
            for (var identifier : new String[] {known, unknown}) {
                for (int i = 0; i < 2; i++) {
                    postJson(
                                    "/api/auth/sign-in/totp",
                                    started(identifier, newIp()),
                                    newIp(),
                                    json(Map.of("code", "000000")))
                            .andExpect(status().isUnprocessableContent())
                            .andExpect(jsonPath("$.errors[0].message")
                                    .value("That code didn't work. Check it and try again."));
                }
                var body = postJson(
                                "/api/auth/sign-in/totp",
                                started(identifier, newIp()),
                                newIp(),
                                json(Map.of("code", "000000")))
                        .andExpect(rateLimited())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                assertThat(body).doesNotContain(identifier);
            }
        }

        @Test
        void wrongBackupCodes_lockTheAccount() throws Exception {
            var user = register(newPerson());
            for (int i = 0; i < 2; i++) {
                postJson(
                                "/api/auth/sign-in/backup-code",
                                started(user.person().email(), newIp()),
                                newIp(),
                                json(Map.of("code", "aaaaa-bbbbb")))
                        .andExpect(status().isUnprocessableContent());
            }
            postJson(
                            "/api/auth/sign-in/backup-code",
                            started(user.person().email(), newIp()),
                            newIp(),
                            json(Map.of("code", "aaaaa-bbbbb")))
                    .andExpect(rateLimited());
            // Other factors keep their own counters (recovery with a backup code must not be blocked by TOTP guessing).
            postJson(
                            "/api/auth/sign-in/totp",
                            started(user.person().email(), newIp()),
                            newIp(),
                            json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
        }

        @Test
        void failedPasskeyAssertions_areLimitedPerSession() throws Exception {
            var session = new MockHttpSession();
            var ip = newIp();
            for (int i = 0; i < 3; i++) {
                mvc.perform(from(post("/api/auth/sign-in/passkey/options"), ip).session(session))
                        .andExpect(status().isOk());
                var result = postJson("/api/auth/sign-in/passkey", session, ip, "{\"credential\":{\"id\":\"bogus\"}}");
                if (i < 2) {
                    result.andExpect(status().isUnprocessableContent());
                } else {
                    result.andExpect(rateLimited());
                }
            }
        }
    }

    @Nested
    class StepUp {

        @Test
        void failures_countPerUser_acrossSessions() throws Exception {
            var user = register(newPerson());
            postJson("/api/auth/step-up/totp", user.session(), newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());
            postJson("/api/auth/step-up/totp", user.session(), newIp(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent());

            // A second signed-in session of the same person continues the count.
            var second = new MockHttpSession();
            postJson(
                            "/api/auth/sign-in",
                            second,
                            newIp(),
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            postJson("/api/auth/sign-in/totp", second, newIp(), json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
            clock.advanceSeconds(30);
            postJson("/api/auth/step-up/totp", second, newIp(), json(Map.of("code", "000000")))
                    .andExpect(rateLimited());
            postJson("/api/auth/step-up/totp", second, newIp(), json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(rateLimited());
            assertThat(auditRows(user.userId())).isEqualTo(1);
        }
    }

    @Nested
    class SecurityChanges {

        @Test
        void revokingAndRemoving_areLimitedPerUser() throws Exception {
            var user = register(newPerson());
            for (int i = 0; i < 3; i++) {
                postJson("/api/auth/security/sessions/01J9ZD3V00000000000000NONE/revoke", user.session(), newIp(), "{}")
                        .andExpect(status().isNotFound());
            }
            postJson("/api/auth/security/sessions/revoke-others", user.session(), newIp(), "{}")
                    .andExpect(rateLimited());
            mvc.perform(from(delete("/api/auth/security/passkeys/{id}", "x"), newIp())
                            .session(user.session()))
                    .andExpect(rateLimited());
        }
    }

    @Nested
    class ClientCity {

        private String cityOfLastSignIn(String userId) {
            return jdbc.sql(
                            "SELECT coalesce(city, '') FROM identity.sessions WHERE user_id = :u ORDER BY id DESC LIMIT 1")
                    .param("u", userId)
                    .query(String.class)
                    .single();
        }

        private void signIn(Registered user, String peer) throws Exception {
            clock.advanceSeconds(30);
            var session = new MockHttpSession();
            postJson(
                            "/api/auth/sign-in",
                            session,
                            newIp(),
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            mvc.perform(from(post("/api/auth/sign-in/totp"), peer)
                            .header("X-Client-City", "Montr%C3%A9al")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("code", totpNow(user.totpSecret())))))
                    .andExpect(status().isOk());
        }

        @Test
        void theCityHeader_isBelievedFromTrustedProxiesOnly() throws Exception {
            var user = register(newPerson());
            signIn(user, "10.1.2.3");
            assertThat(cityOfLastSignIn(user.userId())).isEqualTo("Montréal");
            signIn(user, newIp());
            assertThat(cityOfLastSignIn(user.userId())).isEmpty();
        }
    }

    @Nested
    class ClientIp {

        @Test
        void forwardedFor_fromATrustedProxy_isTheClient() throws Exception {
            var client = newIp();
            for (int i = 0; i < 4; i++) {
                lookupVia("10.1.2.3", client).andExpect(status().isOk());
            }
            lookupVia("10.9.9.9", client).andExpect(rateLimited()); // another proxy, same client
            lookupVia("10.1.2.3", newIp()).andExpect(status().isOk()); // another client, same proxy
            // Several hops: the right-most address that isn't a trusted proxy.
            lookupVia("10.1.2.3", client + ", 10.4.4.4").andExpect(rateLimited());
            lookupVia("10.1.2.3", "203.0.113.1, " + newIp() + ", 10.4.4.4").andExpect(status().isOk());
        }

        @Test
        void forwardedFor_fromAnyoneElse_isIgnored() throws Exception {
            var peer = newIp(); // not in northline.auth.trusted-proxies (10.0.0.0/8)
            for (int i = 0; i < 4; i++) {
                lookupVia(peer, newIp()).andExpect(status().isOk()); // a new "client" each time…
            }
            lookupVia(peer, newIp()).andExpect(rateLimited()); // …all counted against the real peer
        }

        private ResultActions lookupVia(String peer, String forwardedFor) throws Exception {
            return mvc.perform(from(post("/api/auth/sign-in"), peer)
                    .header("X-Forwarded-For", forwardedFor)
                    .session(new MockHttpSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("identifier", "ip-test@example.ca"))));
        }
    }
}
