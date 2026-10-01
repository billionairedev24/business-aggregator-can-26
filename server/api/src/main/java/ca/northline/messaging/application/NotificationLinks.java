package ca.northline.messaging.application;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Links in notification emails ({@code northline.notifications.*}): Studio pages ({@code STUDIO_ORIGIN}) and the
 * unsubscribe endpoint, which must be reachable without a Studio session — so it points at the api's public URL
 * ({@code API_PUBLIC_URL}), not the BFF.
 *
 * @param studioUrl {@code STUDIO_ORIGIN}, default {@code http://localhost:3100}
 * @param apiUrl {@code API_PUBLIC_URL}, default {@code http://localhost:8080}
 * @param unsubscribeKey {@code EMAIL_UNSUBSCRIBE_KEY} — HMAC key of unsubscribe tokens (blank = a fixed development
 *     key; refused under staging/prod)
 */
@ConfigurationProperties("northline.notifications")
public record NotificationLinks(
        @Nullable String studioUrl,
        @Nullable String apiUrl,
        @Nullable String unsubscribeKey) {

    public static final String UNSUBSCRIBE_PATH = "/api/v1/email/unsubscribe";

    /** {@code <studio>/b/<merchantId>/<page>}, e.g. {@code payouts}, {@code refunds}. */
    public URI studio(String merchantId, String page) {
        return URI.create(base(studioUrl, "http://localhost:3100") + "/b/" + encode(merchantId) + "/" + page);
    }

    /** {@code <studio>/b/<merchantId>}: the business's Studio home. */
    public URI studioHome(String merchantId) {
        return URI.create(base(studioUrl, "http://localhost:3100") + "/b/" + encode(merchantId));
    }

    /** {@code <studio>/onboarding/verification?m=<merchantId>}: the onboarding wizard's Verification step. */
    public URI onboardingVerification(String merchantId) {
        return URI.create(
                base(studioUrl, "http://localhost:3100") + "/onboarding/verification?m=" + encode(merchantId));
    }

    public URI unsubscribe(String token) {
        return URI.create(base(apiUrl, "http://localhost:8080") + UNSUBSCRIBE_PATH + "?t=" + encode(token));
    }

    private static String base(@Nullable String url, String fallback) {
        var value = url == null || url.isBlank() ? fallback : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
