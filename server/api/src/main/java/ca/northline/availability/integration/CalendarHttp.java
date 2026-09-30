package ca.northline.availability.integration;

import ca.northline.availability.application.CalendarGateway.GrantRevoked;
import ca.northline.availability.application.CalendarGateway.Unauthorized;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

/** {@code @HttpExchange} clients and small helpers shared by the Google and Microsoft adapters. */
final class CalendarHttp {
    private CalendarHttp() {}

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** JDK HTTP client, connect 5 s, read 20 s; redirects are not followed (token endpoints never redirect). */
    static <T> T client(Class<T> api, String baseUrl) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(Duration.ofSeconds(20));
        var rest = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .requestFactory(requests)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(api);
    }

    static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    static MultiValueMap<String, String> form(String... pairs) {
        var form = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /** {@code ?a=b&c=d} with RFC 3986 percent-encoding (spaces as %20). */
    static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Runs a call made with an access token: 401 becomes {@link Unauthorized}. */
    static <T> T authorized(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new Unauthorized("401 from the provider");
        }
    }

    static void authorizedRun(Runnable call) {
        authorized(() -> {
            call.run();
            return true;
        });
    }

    /** Runs a token-endpoint call: {@code invalid_grant} (and 401 {@code invalid_client}) become {@link GrantRevoked}. */
    static JsonNode tokenCall(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            var error = errorCode(e);
            if ("invalid_grant".equals(error) || e.getStatusCode().value() == 401) {
                throw new GrantRevoked(
                        error == null ? "token endpoint " + e.getStatusCode().value() : error);
            }
            throw e;
        }
    }

    static @Nullable String errorCode(HttpClientErrorException e) {
        try {
            var body = JSON.readTree(e.getResponseBodyAsString());
            var error = body.path("error");
            if (error.isString()) {
                return error.asString();
            }
            var code = error.path("code");
            return code.isString() ? code.asString() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    static Instant expiresAt(JsonNode token, Instant now) {
        return now.plusSeconds(token.path("expires_in").asLong(3600));
    }

    static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        return v.isString() && !v.asString().isBlank() ? v.asString() : null;
    }

    /**
     * Claims of an ID token received directly from the provider's token endpoint over TLS. Its signature is not checked
     * (OpenID Connect Core 3.1.3.7, rule 6: TLS server validation may replace it for a code-flow token endpoint); only
     * the account id and a label are taken from it.
     */
    static JsonNode idTokenClaims(@Nullable String idToken) {
        if (idToken == null) {
            return JSON.createObjectNode();
        }
        var parts = idToken.split("\\.");
        if (parts.length < 2) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }
}
