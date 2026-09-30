package ca.northline.payments.infra;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.payments.*}.
 *
 * @param stripeSecretKey Stripe platform secret key; when blank the local fake gateway is used
 * @param stripePublishableKey for Stripe.js (Financial Connections in the Studio)
 * @param evidenceDir where dispute evidence is written under local/test
 * @param stripeApiBase Stripe API base URL override, e.g. {@code http://localhost:12111} for stripe-mock; blank = Stripe
 * @param stripeWebhookSecret signing secret ({@code whsec_…}) of the platform webhook endpoint
 * @param stripeConnectWebhookSecret signing secret of the Connect webhook endpoint (connected accounts' events)
 * @param stripeWebhookTolerance how old a signed delivery may be (replay protection); default 5 minutes
 * @param webhookRateLimit webhook deliveries accepted per minute and client address; default 600
 */
@ConfigurationProperties("northline.payments")
record PaymentsProperties(
        @Nullable String stripeSecretKey,
        @Nullable String stripePublishableKey,
        @Nullable Path evidenceDir,
        @Nullable String stripeApiBase,
        @Nullable String stripeWebhookSecret,
        @Nullable String stripeConnectWebhookSecret,
        @Nullable Duration stripeWebhookTolerance,
        @Nullable Integer webhookRateLimit) {

    static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);
    static final int DEFAULT_RATE_LIMIT = 600;

    Duration tolerance() {
        return stripeWebhookTolerance == null ? DEFAULT_TOLERANCE : stripeWebhookTolerance;
    }

    /** Live keys ({@code sk_live_…} / {@code rk_live_…}) → live-mode events are the ones to act on. */
    boolean livemode() {
        var key = stripeSecretKey;
        return key != null && (key.startsWith("sk_live_") || key.startsWith("rk_live_"));
    }
}
