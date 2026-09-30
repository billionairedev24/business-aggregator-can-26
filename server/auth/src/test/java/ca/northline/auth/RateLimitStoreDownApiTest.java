package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * S-20: Valkey unreachable (nothing listens on the configured port) with {@code when-unavailable: closed}, the
 * staging/prod policy — phone codes and second factors answer {@code 503 sign_in_unavailable} with {@code Retry-After}
 * instead of going through without a limit; typing the email or mobile (no secret involved) still works.
 */
@TestPropertySource(
        properties = {
            "northline.auth.rate-limits.store=redis",
            "northline.auth.rate-limits.when-unavailable=closed",
            "spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=1",
            "spring.data.redis.timeout=500ms",
            "spring.data.redis.connect-timeout=500ms",
        })
class RateLimitStoreDownApiTest extends AuthIntegrationTest {

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static ResultMatcher unavailable() {
        return result -> {
            status().isServiceUnavailable().match(result);
            jsonPath("$.code").value("sign_in_unavailable").match(result);
            jsonPath("$.detail")
                    .value("Signing in is paused for a few minutes while we fix a problem on our side. Try again"
                            + " shortly.")
                    .match(result);
            jsonPath("$.retryAfterSeconds").value(30).match(result);
            header().string("Retry-After", "30").match(result);
        };
    }

    @Test
    void noPhoneCodeIsSent() throws Exception {
        var person = newPerson();
        postJson("/api/auth/register", new MockHttpSession(), person.json()).andExpect(unavailable());
        assertThat(sms.sentTo(person.e164())).isEmpty();
    }

    @Test
    void theLookupFailsOpen_butEverySecondFactorFailsClosed() throws Exception {
        var session = new MockHttpSession();
        postJson(
                        "/api/auth/sign-in",
                        session,
                        json(Map.of("identifier", "someone-" + System.nanoTime() + "@example.ca")))
                .andExpect(status().isOk());
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", "123456")))
                .andExpect(unavailable());
        postJson("/api/auth/sign-in/backup-code", session, json(Map.of("code", "abcde-fghij")))
                .andExpect(unavailable());
        mvc.perform(post("/api/auth/sign-in/passkey/options").session(session)).andExpect(status().isOk());
        postJson("/api/auth/sign-in/passkey", session, "{\"credential\":{\"id\":\"x\"}}")
                .andExpect(unavailable());
    }
}
