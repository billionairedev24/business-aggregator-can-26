package ca.northline.food.adapters.pos;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.pos.*} (S-36, docs/runbooks/pos-menu-import.md).
 *
 * @param provider {@code local} (fake Square / Clover / Toast with a fixture menu; refused under staging/prod) |
 *     {@code oauth} (the real APIs, each offered once its credentials are set) — {@code POS_PROVIDER}
 */
@ConfigurationProperties("northline.pos")
record PosProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("http://localhost:8080") URI apiUrl,
        @DefaultValue("http://localhost:3100") URI studioUrl,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("PT30S") Duration maxBackoff,
        @DefaultValue Square square,
        @DefaultValue Clover clover,
        @DefaultValue Toast toast) {

    String effectiveProvider() {
        return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean present(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static String strip(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    /** The same Square application as the S-35 catalogue sync ({@code SQUARE_CLIENT_ID} / {@code _SECRET}). */
    record Square(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @DefaultValue("https://connect.squareup.com") String baseUrl,
            @DefaultValue("2025-10-16") String apiVersion,

            @DefaultValue("ITEMS_READ MERCHANT_PROFILE_READ")
            String scopes) {
        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return strip(clientId);
        }

        String secret() {
            return strip(clientSecret);
        }
    }

    /**
     * Clover app ({@code CLOVER_CLIENT_ID} = App ID, {@code CLOVER_CLIENT_SECRET} = App Secret). North America:
     * {@code https://www.clover.com} / {@code https://api.clover.com}; sandbox {@code https://sandbox.dev.clover.com} /
     * {@code https://apisandbox.dev.clover.com}.
     */
    record Clover(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @DefaultValue("https://www.clover.com") String authUrl,
            @DefaultValue("https://api.clover.com") String apiUrl) {
        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return strip(clientId);
        }

        String secret() {
            return strip(clientSecret);
        }
    }

    /**
     * Toast partner API credentials ({@code TOAST_CLIENT_ID} / {@code TOAST_CLIENT_SECRET}, machine client) — issued
     * by Toast to approved partners only. {@code apiUrl} {@code https://ws-sandbox-api.eng.toasttab.com} = sandbox.
     */
    record Toast(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @DefaultValue("https://ws-api.toasttab.com") String apiUrl) {
        boolean configured() {
            return present(clientId) && present(clientSecret);
        }

        String id() {
            return strip(clientId);
        }

        String secret() {
            return strip(clientSecret);
        }
    }
}
