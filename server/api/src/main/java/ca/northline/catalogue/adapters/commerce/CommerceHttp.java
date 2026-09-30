package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.CommerceCatalogSource.GrantRevoked;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code @HttpExchange} clients, HMAC and back-off shared by the Shopify, Square and Lightspeed adapters. */
final class CommerceHttp {
    private CommerceHttp() {}

    static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * JDK HTTP client, connect 5 s, read 30 s; redirects are not followed. The interfaces take the full {@link URI} of
     * each call (shop-specific hosts), so the client has no base URL.
     */
    static <T> T client(Class<T> api) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(Duration.ofSeconds(30));
        var rest = RestClient.builder().requestFactory(requests).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(api);
    }

    static URI uri(String base, String path, Map<String, String> query) {
        var b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(b + path + (query.isEmpty() ? "" : query(query)));
    }

    static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    /** {@code ?a=b&c=d} with RFC 3986 percent-encoding (spaces as %20). */
    static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static MultiValueMap<String, String> form(String... pairs) {
        var form = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    static byte[] hmacSha256(String key, byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of two signatures in the same encoding. */
    static boolean same(@Nullable String presented, String expected) {
        return presented != null
                && MessageDigest.isEqual(
                        presented.strip().getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII));
    }

    static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        if (v.isString() && !v.asString().isBlank()) {
            return v.asString();
        }
        return v.isNumber() ? v.asString() : null;
    }

    /** Dollars as a decimal string or number ("24.99") → cents. */
    static long cents(JsonNode amount) {
        if (amount.isMissingNode() || amount.isNull()) {
            return 0;
        }
        return new java.math.BigDecimal(amount.asString())
                .movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Runs a token-endpoint call: 400 {@code invalid_grant} and 401 become {@link GrantRevoked}. */
    static JsonNode tokenCall(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            var body = e.getResponseBodyAsString();
            if (body.contains("invalid_grant") || e.getStatusCode().value() == 401) {
                throw new GrantRevoked("token endpoint " + e.getStatusCode().value());
            }
            throw e;
        }
    }

    /** Runs an API call made with an access token: 401 (and Shopify's 401/403 for an uninstalled app) → reconnect. */
    static <T> T authorized(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new GrantRevoked("401 from the platform");
        }
    }

    /** Waits between attempts; tests keep the waits short with {@code northline.commerce.max-backoff}. */
    interface Sleeper {
        void sleep(Duration duration);
    }

    /**
     * Retries a call the platform rate-limited (429) or couldn't serve (502/503/504): waits what {@code Retry-After}
     * says (seconds or an HTTP date), else 0.5 s, 1 s, 2 s … — each wait capped at {@code maxBackoff}; after
     * {@code maxRetries} the error goes up and the sync is retried on its next run.
     */
    @Slf4j
    static final class Backoff {
        private final int maxRetries;
        private final Duration maxBackoff;
        private final Sleeper sleeper;
        private final Clock clock;

        Backoff(int maxRetries, Duration maxBackoff, Sleeper sleeper, Clock clock) {
            this.maxRetries = maxRetries;
            this.maxBackoff = maxBackoff;
            this.sleeper = sleeper;
            this.clock = clock;
        }

        <T> T call(String what, Supplier<T> call) {
            for (int attempt = 0; ; attempt++) {
                try {
                    return call.get();
                } catch (HttpClientErrorException.TooManyRequests
                        | HttpServerErrorException.ServiceUnavailable
                        | HttpServerErrorException.BadGateway
                        | HttpServerErrorException.GatewayTimeout e) {
                    if (attempt >= maxRetries) {
                        throw e;
                    }
                    var wait = retryAfter(e).orElse(Duration.ofMillis(500L << Math.min(attempt, 10)));
                    log.info("{}: {} — retry {} of {} in {}", what, e.getStatusCode(), attempt + 1, maxRetries, wait);
                    pause(wait);
                }
            }
        }

        void pause(Duration wait) {
            sleeper.sleep(wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait);
        }

        int maxRetries() {
            return maxRetries;
        }

        java.util.Optional<Duration> retryAfter(HttpStatusCodeException e) {
            var headers = e.getResponseHeaders();
            var value = headers == null ? null : headers.getFirst("Retry-After");
            if (value == null || value.isBlank()) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(Duration.ofMillis((long) (Double.parseDouble(value.strip()) * 1000)));
            } catch (NumberFormatException _) {
                try {
                    var at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                    var d = Duration.between(clock.instant(), at);
                    return java.util.Optional.of(d.isNegative() ? Duration.ZERO : d);
                } catch (DateTimeParseException _) {
                    try { // Lightspeed X-Series sends an ISO-8601 instant
                        var d = Duration.between(clock.instant(), java.time.Instant.parse(value.strip()));
                        return java.util.Optional.of(d.isNegative() ? Duration.ZERO : d);
                    } catch (DateTimeParseException _) {
                        return java.util.Optional.empty();
                    }
                }
            }
        }
    }
}
