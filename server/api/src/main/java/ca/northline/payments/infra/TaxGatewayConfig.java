package ca.northline.payments.infra;

import ca.northline.payments.application.TaxGateway;
import ca.northline.shared.stripe.StripeClients;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Picks the sales-tax adapter by {@code northline.tax.provider} ({@code TAX_PROVIDER}): {@code stripe} → Stripe Tax
 * through stripe-java with the payments key (with {@code STRIPE_API_BASE} it talks to stripe-mock); {@code local} →
 * fixed Canadian rates. Staging and prod refuse {@code local}: tax must be what Stripe Tax files.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaxProperties.class)
class TaxGatewayConfig {

    @Bean
    TaxGateway taxGateway(TaxProperties tax, PaymentsProperties payments, Environment environment, Clock clock) {
        return switch (tax.provider()) {
            case LOCAL -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException(
                            "TAX_PROVIDER=local (fixed tax rates) is refused under staging and prod: set TAX_PROVIDER=stripe"
                                    + " (docs/runbooks/stripe.md § 6).");
                }
                log.info("Tax: local fixed Canadian rates (TAX_PROVIDER=local) — nothing is reported to Stripe Tax.");
                yield new LocalTaxGateway(clock);
            }
            case STRIPE -> {
                var key = payments.stripeSecretKey();
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("TAX_PROVIDER=stripe needs STRIPE_SECRET_KEY.");
                }
                log.info("Tax: Stripe Tax (TAX_PROVIDER=stripe).");
                yield new StripeTaxGateway(StripeClients.create(key, payments.stripeApiBase()), tax, clock);
            }
        };
    }
}
