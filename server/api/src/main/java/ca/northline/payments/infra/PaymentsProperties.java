package ca.northline.payments.infra;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.payments.*}.
 *
 * @param stripeSecretKey Stripe platform secret key; when blank the local fake gateway is used
 * @param stripePublishableKey for Stripe.js (Financial Connections in the Studio)
 * @param evidenceDir where dispute evidence is written under local/test
 */
@ConfigurationProperties("northline.payments")
record PaymentsProperties(
        @Nullable String stripeSecretKey,
        @Nullable String stripePublishableKey,
        @Nullable Path evidenceDir) {}
