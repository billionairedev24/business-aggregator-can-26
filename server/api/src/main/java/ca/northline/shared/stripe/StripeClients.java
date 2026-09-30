package ca.northline.shared.stripe;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.net.HttpClient;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link StripeClient} every Stripe adapter uses. The API version is the one stripe-java is compiled against
 * ({@link Stripe#API_VERSION}); {@link #PINNED_API_VERSION} repeats it on purpose so an SDK upgrade that moves the
 * version fails fast (and in {@code StripeClientsTest}) until the webhook endpoints and docs are moved with it
 * (docs/runbooks/stripe.md). {@code apiBase} points the client at stripe-mock ({@code STRIPE_API_BASE}); blank = Stripe.
 */
public final class StripeClients {

    /** Stripe API version Northline is built and tested against; webhook endpoints must be created with it. */
    public static final String PINNED_API_VERSION = "2026-08-26.dahlia";

    /** stripe-java retries network failures and 409/429/5xx; our idempotency keys make those retries safe. */
    static final int MAX_NETWORK_RETRIES = 2;

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 30_000;

    private StripeClients() {}

    public static StripeClient create(String secretKey, @Nullable String apiBase) {
        return create(secretKey, apiBase, null);
    }

    /** {@code httpClient} = a test double around the default transport; null = stripe-java's default. */
    public static StripeClient create(String secretKey, @Nullable String apiBase, @Nullable HttpClient httpClient) {
        requirePinnedVersion(Stripe.API_VERSION);
        if (secretKey.isBlank()) {
            throw new IllegalArgumentException("Stripe secret key is blank");
        }
        var builder = StripeClient.builder()
                .setApiKey(secretKey)
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS);
        if (apiBase != null && !apiBase.isBlank()) {
            builder.setApiBase(apiBase).setConnectBase(apiBase).setFilesBase(apiBase);
        }
        if (httpClient != null) {
            builder.setHttpClient(httpClient);
        }
        return builder.build();
    }

    static void requirePinnedVersion(String sdkVersion) {
        if (!PINNED_API_VERSION.equals(sdkVersion)) {
            throw new IllegalStateException("stripe-java speaks Stripe API " + sdkVersion
                    + " but Northline is pinned to "
                    + PINNED_API_VERSION + ": move the webhook endpoints and StripeClients.PINNED_API_VERSION together"
                    + " (docs/runbooks/stripe.md)");
        }
    }
}
