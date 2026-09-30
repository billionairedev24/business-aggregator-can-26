package ca.northline.auth.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The Apple client secret JWT: claims, renewal before expiry, Apple's 6-month cap, and every problem at start-up. */
class AppleClientSecretTest {

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
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
            return now;
        }
    }

    private static String pem() throws Exception {
        var ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        return "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder()
                        .encodeToString(ec.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----"; // as pasted into one line of an env file
    }

    private static FederationProperties.Apple apple(String pem, Duration ttl) {
        return new FederationProperties.Apple(
                "ca.northline.test", "TESTTEAM01", "TESTKEY001", pem, ttl, "a", "t", "k", "https://appleid.apple.com");
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    void isReused_thenRenewedWhenAQuarterOfItsLifeIsLeft() throws Exception {
        var clock = new MovingClock();
        var secret = new AppleClientSecret(apple(pem(), Duration.ofDays(30)), clock);
        var first = secret.current();
        assertThat(JsonPath.<Integer>read(claims(first), "$.exp") - JsonPath.<Integer>read(claims(first), "$.iat"))
                .isEqualTo(30 * 86_400);
        clock.advance(Duration.ofDays(22));
        assertThat(secret.current()).isEqualTo(first);
        clock.advance(Duration.ofDays(1)); // 7 days left < 7.5
        var second = secret.current();
        assertThat(second).isNotEqualTo(first);
        assertThat(secret.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void neverLongerThanAppleAllows() throws Exception {
        var secret = new AppleClientSecret(apple(pem(), Duration.ofDays(365)), new MovingClock());
        var jwt = secret.current();
        assertThat(JsonPath.<Integer>read(claims(jwt), "$.exp") - JsonPath.<Integer>read(claims(jwt), "$.iat"))
                .isEqualTo(180 * 86_400);
    }

    @Test
    void listsEveryProblem() {
        var broken = new FederationProperties.Apple(
                "ca.northline.test",
                " ",
                null,
                "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----",
                Duration.ofDays(30),
                "a",
                "t",
                "k",
                "i");
        assertThatThrownBy(() -> new AppleClientSecret(broken, new MovingClock()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPLE_TEAM_ID")
                .hasMessageContaining("APPLE_KEY_ID")
                .hasMessageContaining("APPLE_PRIVATE_KEY is not a PKCS#8 EC P-256 key");
    }
}
