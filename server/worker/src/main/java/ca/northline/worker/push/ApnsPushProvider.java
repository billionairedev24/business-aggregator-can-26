package ca.northline.worker.push;

import ca.northline.worker.notifications.PushApp;
import ca.northline.worker.notifications.PushSender;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * APNs with token-based authentication (the {@code .p8} key): a provider JWT ({@code ES256}, {@code kid} = key id,
 * {@code iss} = team id, {@code iat}) reused for 50 minutes — Apple refuses tokens older than an hour and too-frequent
 * new ones ({@code TooManyProviderTokenUpdates}). The topic is the app's bundle id.
 *
 * <p>Answers: 200 delivered · 410 {@code Unregistered}/{@code ExpiredToken}, 400 {@code BadDeviceToken} /
 * {@code DeviceTokenNotForTopic} → the token is dead · 403 {@code ExpiredProviderToken}/{@code InvalidProviderToken}
 * → a new JWT and one more try · 429 → throttled · 5xx, time-outs → unavailable · any other 4xx → refused.
 *
 * <p><b>Never run against Apple</b> (no Apple developer account or key yet): tested against WireMock only.
 */
@Slf4j
public final class ApnsPushProvider implements PushProvider {

    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(50);
    static final Set<String> DEAD_TOKEN =
            Set.of("Unregistered", "ExpiredToken", "BadDeviceToken", "DeviceTokenNotForTopic");
    static final Set<String> STALE_JWT = Set.of("ExpiredProviderToken", "InvalidProviderToken");

    private final ApnsApi api;
    private final String keyId;
    private final String teamId;
    private final PrivateKey key;
    private final String consumerTopic;
    private final String courierTopic;
    private final JsonMapper json;
    private final Clock clock;
    private @Nullable String jwt;
    private Instant jwtIssuedAt = Instant.EPOCH;

    public ApnsPushProvider(
            ApnsApi api,
            String keyId,
            String teamId,
            String pem,
            String consumerTopic,
            String courierTopic,
            JsonMapper json,
            Clock clock) {
        this.api = api;
        this.keyId = keyId;
        this.teamId = teamId;
        this.key = PemKeys.privateKey(pem, "EC");
        this.consumerTopic = consumerTopic;
        this.courierTopic = courierTopic;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String platform() {
        return "ios";
    }

    @Override
    public Outcome send(PushDevice device, PushSender.Content words, PushSender.PushMessage message) {
        var payload = json.writeValueAsString(payload(words, message));
        var outcome = attempt(device, payload, message, false);
        if (outcome instanceof Outcome.Unavailable down && STALE_JWT.contains(down.reason())) {
            outcome = attempt(device, payload, message, true);
        }
        return outcome;
    }

    /** The APNs payload: the alert, and the deep link and ids for the app — nothing else. */
    static Map<String, Object> payload(PushSender.Content words, PushSender.PushMessage message) {
        var aps = new LinkedHashMap<String, Object>();
        aps.put("alert", Map.of("title", words.title(), "body", words.body()));
        aps.put("sound", "default");
        aps.put("thread-id", message.collapseKey());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("aps", aps);
        var link = message.link();
        if (link != null) {
            payload.put("link", link.toString());
        }
        payload.put("data", message.data());
        return payload;
    }

    private Outcome attempt(PushDevice device, String payload, PushSender.PushMessage message, boolean freshJwt) {
        var headers = new LinkedMultiValueMap<String, String>();
        headers.add("authorization", "bearer " + jwt(freshJwt));
        headers.add("apns-topic", message.app() == PushApp.COURIER ? courierTopic : consumerTopic);
        headers.add("apns-push-type", "alert");
        headers.add("apns-priority", "10");
        headers.add(
                "apns-expiration",
                Long.toString(clock.instant().plus(Duration.ofDays(1)).getEpochSecond()));
        headers.add("apns-collapse-id", message.collapseKey());
        try {
            api.send(device.token(), headers, payload);
            return new Outcome.Delivered();
        } catch (RestClientResponseException e) {
            var reason = reason(e.getResponseBodyAsString());
            var status = e.getStatusCode().value();
            var answered = e.getResponseHeaders();
            var retryAfter = PushProvider.retryAfter(
                    answered == null ? null : answered.getFirst("Retry-After"), clock.instant());
            if (DEAD_TOKEN.contains(reason) || status == 410) {
                return new Outcome.InvalidToken(reason);
            }
            if (status == 429) {
                return new Outcome.Throttled(retryAfter);
            }
            if (status == 403 || status >= 500) {
                return new Outcome.Unavailable(reason.isEmpty() ? "HTTP " + status : reason, retryAfter);
            }
            return new Outcome.Rejected("HTTP " + status + " " + reason);
        } catch (ResourceAccessException e) {
            return new Outcome.Unavailable("no answer: " + e.getMessage(), null);
        }
    }

    private String reason(String body) {
        try {
            return json.readTree(body).path("reason").asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private synchronized String jwt(boolean fresh) {
        var now = clock.instant();
        var current = jwt;
        if (current != null && !fresh && now.isBefore(jwtIssuedAt.plus(TOKEN_LIFETIME))) {
            return current;
        }
        var header = json.writeValueAsString(Map.of("alg", "ES256", "kid", keyId));
        var claims = json.writeValueAsString(Map.of("iss", teamId, "iat", now.getEpochSecond()));
        var minted = PemKeys.sign(header, claims, key);
        jwt = minted;
        jwtIssuedAt = now;
        log.debug("APNs provider token renewed (key {})", keyId);
        return minted;
    }
}
