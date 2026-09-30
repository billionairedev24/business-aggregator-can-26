package ca.northline.auth.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.auth.domain.Totp;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for auth-server integration tests: full context, MockMvc, profile {@code test}, Flyway-migrated PostGIS
 * (one container per JVM), a controllable {@link MutableClock} and the SMS codes captured by {@link RecordingSms}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthIntegrationTest.Fakes.class)
public abstract class AuthIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = SharedPostgres.INSTANCE;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RecordingSms sms;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    /** A person nobody registered yet. */
    public record Person(String firstName, String lastName, String phone, String email) {
        public String e164() {
            return PhoneNumber.parse(phone).orElseThrow().e164();
        }

        public String json() {
            return """
                    {"firstName":"%s","lastName":"%s","phone":"%s","email":"%s","terms":true}""".formatted(firstName, lastName, phone, email);
        }
    }

    /** The result of a completed registration through the API. */
    public record Registered(String userId, Person person, String totpSecret, MockHttpSession session) {
        /** The mobile as 10 bare digits (another accepted input format). */
        public String e164Digits() {
            return person.e164().substring(2);
        }
    }

    protected static Person newPerson() {
        var r = ThreadLocalRandom.current();
        var n = r.nextInt(1_000_000, 9_999_999);
        return new Person(
                "Amara",
                "Osei",
                "+1 (587) %03d-%04d".formatted(n / 10_000 % 1000, n % 10_000),
                "amara." + n + "@example.ca");
    }

    protected static String json(Map<String, ?> body) {
        var sb = new StringBuilder("{");
        body.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "")
                .append('"')
                .append(k)
                .append("\":")
                .append(v instanceof String s ? "\"" + s + "\"" : String.valueOf(v)));
        return sb.append('}').toString();
    }

    protected String totpNow(String secret) {
        return Totp.codeAt(secret, Totp.step(clock.instant()));
    }

    /** Hook for every request {@link #register} sends (the rate-limit tests give each registration its own IP). */
    protected MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        return request;
    }

    /** Registers through the API with an authenticator app; the returned session is signed in with acr=mfa. */
    protected Registered register(Person person) throws Exception {
        return register(person, new MockHttpSession());
    }

    /** Registers in this auth session (e.g. one that came back from Google / Apple). */
    protected Registered register(Person person, MockHttpSession session) throws Exception {
        mvc.perform(client(post("/api/auth/register"))
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(person.json()))
                .andExpect(status().isOk());
        mvc.perform(client(post("/api/auth/register/verify"))
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", sms.lastCodeTo(person.e164())))))
                .andExpect(status().isOk());
        var setup = mvc.perform(client(post("/api/auth/register/totp")).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        var created = mvc.perform(client(post("/api/auth/register/totp/verify"))
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(secret)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        clock.advanceSeconds(Totp.PERIOD_SECONDS); // the registration code's step is used up
        return new Registered(JsonPath.read(created, "$.user.id"), person, secret, session);
    }

    /** Test doubles: a settable clock and an SMS sender that remembers codes. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        RecordingSms recordingSms() {
            return new RecordingSms();
        }
    }

    /** Clock the tests move forward (code expiry, resend cool-down, TOTP steps). */
    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advanceSeconds(long seconds) {
            now.updateAndGet(i -> i.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Captures every code "sent". */
    public static final class RecordingSms implements SmsSender {
        private final Map<String, List<Sent>> sent = new ConcurrentHashMap<>();

        /** One delivery. */
        public record Sent(String code, Channel channel, Locale locale) {}

        private volatile SmsDeliveryFailed.@Nullable Kind failNext;

        /** The next send fails like a provider would (then sending works again). */
        public void failNext(SmsDeliveryFailed.Kind kind) {
            failNext = kind;
        }

        @Override
        public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
            var failure = failNext;
            if (failure != null) {
                failNext = null;
                throw new SmsDeliveryFailed(failure, "simulated");
            }
            sent.computeIfAbsent(to.e164(), _ -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(new Sent(code, channel, locale));
        }

        public String lastCodeTo(String e164) {
            return sent.get(e164).getLast().code();
        }

        public List<Sent> sentTo(String e164) {
            return sent.getOrDefault(e164, List.of());
        }
    }
}
