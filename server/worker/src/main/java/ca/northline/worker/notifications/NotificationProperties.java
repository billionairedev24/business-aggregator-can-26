package ca.northline.worker.notifications;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.notifications.*} — the same variables as the api's S-13 links: Studio pages ({@code STUDIO_ORIGIN})
 * and the api's public unsubscribe endpoint ({@code API_PUBLIC_URL}), signed with {@code EMAIL_UNSUBSCRIBE_KEY} (the
 * api verifies the tokens the worker issues); since S-102 the consumer host ({@code CONSUMER_ORIGIN}) of the deep links
 * in customers' and couriers' pushes and emails (universal / app links: the apps open them).
 */
@ConfigurationProperties("northline.notifications")
public record NotificationProperties(
        @Nullable String studioUrl,
        @Nullable String apiUrl,
        @Nullable String unsubscribeKey,
        @Nullable String consumerUrl) {

    public static final String UNSUBSCRIBE_PATH = "/api/v1/email/unsubscribe";

    public URI studio(String merchantId, String page) {
        return URI.create(base(studioUrl, "http://localhost:3100") + "/b/" + encode(merchantId) + "/" + page);
    }

    /** A deep link: {@code path} ({@code /app/orders/<id>}) on the consumer host. */
    public URI app(String path) {
        return URI.create(base(consumerUrl, "http://localhost:3000") + path);
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
