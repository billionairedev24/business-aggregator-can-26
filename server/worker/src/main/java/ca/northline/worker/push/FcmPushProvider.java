package ca.northline.worker.push;

import ca.northline.worker.notifications.PushSender;
import java.net.URI;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FCM HTTP v1 with a Firebase service account: an OAuth 2.0 access token (scope {@code firebase.messaging}) from a
 * signed JWT assertion ({@code RS256}), kept until 5 minutes before it expires, then
 * {@code POST /v1/projects/<project>/messages:send}.
 *
 * <p>Answers ({@code error.details[].errorCode}): 200 delivered · {@code UNREGISTERED} (404), {@code SENDER_ID_MISMATCH}
 * (403), {@code INVALID_ARGUMENT} naming the registration token (400) → the token is dead · {@code QUOTA_EXCEEDED}
 * (429) → throttled, with {@code Retry-After} · {@code UNAVAILABLE} (503), {@code INTERNAL} (500), time-outs →
 * unavailable · 401 → a new access token and one more try · anything else → refused.
 *
 * <p><b>Never run against Firebase</b> (no Firebase project yet): tested against WireMock only.
 */
@Slf4j
public final class FcmPushProvider implements PushProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final Duration EARLY = Duration.ofMinutes(5);

    private final FcmApi api;
    private final String project;
    private final String clientEmail;
    private final @Nullable String keyId;
    private final PrivateKey key;
    private final URI tokenUri;
    private final JsonMapper json;
    private final Clock clock;
    private @Nullable String accessToken;
    private Instant accessExpires = Instant.EPOCH;

    /**
     * @param serviceAccount the service account's JSON key ({@code project_id}, {@code client_email},
     *     {@code private_key}, {@code private_key_id}, {@code token_uri})
     * @param tokenUrl overrides {@code token_uri} (tests); null = the key's
     */
    public FcmPushProvider(FcmApi api, String serviceAccount, @Nullable String tokenUrl, JsonMapper json, Clock clock) {
        var account = json.readTree(serviceAccount);
        this.api = api;
        this.project = required(account, "project_id");
        this.clientEmail = required(account, "client_email");
        this.keyId = account.path("private_key_id").isString()
                ? account.path("private_key_id").asString()
                : null;
        this.key = PemKeys.privateKey(required(account, "private_key"), "RSA");
        this.tokenUri = URI.create(
                tokenUrl != null && !tokenUrl.isBlank()
                        ? tokenUrl.strip()
                        : account.path("token_uri").asString(DEFAULT_TOKEN_URI));
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String platform() {
        return "android";
    }

    @Override
    public Outcome send(PushDevice device, PushSender.Content words, PushSender.PushMessage message) {
        var body = Map.<String, Object>of("message", message(device, words, message));
        Outcome outcome;
        try {
            outcome = attempt(body, false);
            if (outcome instanceof Outcome.Unavailable down && down.reason().equals("UNAUTHENTICATED")) {
                outcome = attempt(body, true);
            }
        } catch (RestClientResponseException | ResourceAccessException e) {
            return new Outcome.Unavailable("token exchange failed: " + e.getMessage(), null); // OAuth endpoint
        }
        return outcome;
    }

    /** The FCM message: notification words, the deep link and ids as data (strings only), Android collapse key. */
    static Map<String, Object> message(PushDevice device, PushSender.Content words, PushSender.PushMessage message) {
        var data = new LinkedHashMap<String, String>(message.data());
        var link = message.link();
        if (link != null) {
            data.put("link", link.toString());
        }
        var fcm = new LinkedHashMap<String, Object>();
        fcm.put("token", device.token());
        fcm.put("notification", Map.of("title", words.title(), "body", words.body()));
        fcm.put("data", data);
        fcm.put(
                "android",
                Map.of(
                        "collapse_key",
                        message.collapseKey(),
                        "priority",
                        "high",
                        "ttl",
                        "86400s",
                        "notification",
                        Map.of("tag", message.collapseKey(), "channel_id", "updates")));
        return fcm;
    }

    private Outcome attempt(Map<String, Object> body, boolean freshToken) {
        var bearer = "Bearer " + accessToken(freshToken);
        try {
            api.send(project, bearer, body);
            return new Outcome.Delivered();
        } catch (RestClientResponseException e) {
            var status = e.getStatusCode().value();
            var error = error(e.getResponseBodyAsString());
            var code = error.code();
            var answered = e.getResponseHeaders();
            var retryAfter = PushProvider.retryAfter(
                    answered == null ? null : answered.getFirst("Retry-After"), clock.instant());
            if (code.equals("UNREGISTERED") || code.equals("SENDER_ID_MISMATCH")) {
                return new Outcome.InvalidToken(code);
            }
            if (status == 400
                    && error.message().toLowerCase(java.util.Locale.ROOT).contains("registration token")) {
                return new Outcome.InvalidToken("INVALID_ARGUMENT: " + error.message());
            }
            if (status == 429 || code.equals("QUOTA_EXCEEDED")) {
                return new Outcome.Throttled(retryAfter);
            }
            if (status == 401) {
                return new Outcome.Unavailable("UNAUTHENTICATED", null);
            }
            if (status >= 500 || code.equals("UNAVAILABLE") || code.equals("INTERNAL")) {
                return new Outcome.Unavailable(code.isEmpty() ? "HTTP " + status : code, retryAfter);
            }
            return new Outcome.Rejected("HTTP " + status + " " + code + " " + error.message());
        } catch (ResourceAccessException e) {
            return new Outcome.Unavailable("no answer: " + e.getMessage(), null);
        }
    }

    private record FcmError(String code, String message) {}

    private FcmError error(String body) {
        try {
            var error = json.readTree(body).path("error");
            var code = error.path("status").asString("");
            for (JsonNode detail : error.path("details")) {
                if (detail.path("errorCode").isString()) {
                    code = detail.path("errorCode").asString();
                }
            }
            return new FcmError(code, error.path("message").asString(""));
        } catch (RuntimeException e) {
            return new FcmError("", "");
        }
    }

    private synchronized String accessToken(boolean fresh) {
        var now = clock.instant();
        var current = accessToken;
        if (current != null && !fresh && now.isBefore(accessExpires.minus(EARLY))) {
            return current;
        }
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (keyId != null) {
            header.put("kid", keyId);
        }
        var claims = Map.of(
                "iss", clientEmail,
                "scope", SCOPE,
                "aud", tokenUri.toString(),
                "iat", now.getEpochSecond(),
                "exp", now.plus(Duration.ofHours(1)).getEpochSecond());
        var assertion = PemKeys.sign(json.writeValueAsString(header), json.writeValueAsString(claims), key);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);
        var token = api.token(tokenUri, form);
        var expiresIn = token.expiresIn();
        var lifetime = expiresIn == null ? 3600L : expiresIn;
        accessToken = token.accessToken();
        accessExpires = now.plusSeconds(lifetime);
        log.debug("FCM access token renewed for {}", clientEmail);
        return token.accessToken();
    }

    private static String required(JsonNode account, String field) {
        var value = account.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException("PUSH_FCM_SERVICE_ACCOUNT has no " + field + " (docs/runbooks/push.md)");
        }
        return value.asString();
    }
}
