package ca.northline.shared.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code @HttpExchange} clients and small helpers for the platform adapters (S-35 commerce, S-36 POS). */
public final class ProviderHttp {
    private ProviderHttp() {}

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * JDK HTTP client over HTTP/1.1 (no h2c upgrade on plain-http stand-ins), connect 5 s, read 30 s, redirects not
     * followed. The interfaces take the full {@link URI} of each call (merchant-specific hosts), so there's no base URL.
     */
    public static <T> T client(Class<T> api) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(Duration.ofSeconds(30));
        var rest = RestClient.builder().requestFactory(requests).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(api);
    }

    public static URI uri(String base, String path, Map<String, String> query) {
        var b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(b + path + (query.isEmpty() ? "" : query(query)));
    }

    public static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    /** {@code ?a=b&c=d} with RFC 3986 percent-encoding (spaces as %20). */
    public static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    public static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static MultiValueMap<String, String> form(String... pairs) {
        var form = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    public static byte[] hmacSha256(String key, byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of two signatures in the same encoding. */
    public static boolean same(@Nullable String presented, String expected) {
        return presented != null
                && MessageDigest.isEqual(
                        presented.strip().getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII));
    }

    public static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        if (v.isString() && !v.asString().isBlank()) {
            return v.asString();
        }
        return v.isNumber() ? v.asString() : null;
    }

    /** Dollars as a decimal string or number ("24.99") → cents. */
    public static long cents(JsonNode amount) {
        if (amount.isMissingNode() || amount.isNull()) {
            return 0;
        }
        return new BigDecimal(amount.asString())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** A token-endpoint call: 400 {@code invalid_grant} and 401 become {@code revoked.apply(reason)}. */
    public static JsonNode tokenCall(Supplier<JsonNode> call, Function<String, RuntimeException> revoked) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getResponseBodyAsString().contains("invalid_grant")
                    || e.getStatusCode().value() == 401) {
                throw revoked.apply("token endpoint " + e.getStatusCode().value());
            }
            throw e;
        }
    }

    /** A call made with an access token: 401 becomes {@code revoked.apply(reason)}. */
    public static <T> T authorized(Supplier<T> call, Function<String, RuntimeException> revoked) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw revoked.apply("401 from the platform");
        }
    }
}
