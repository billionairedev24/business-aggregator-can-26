package ca.northline.payments.infra;

import ca.northline.payments.application.MembershipBilling;
import ca.northline.shared.stripe.StripeClients;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Picks the Plus billing adapter by {@code northline.plus.billing.provider} ({@code PLUS_BILLING_PROVIDER}, S-152):
 * {@code stripe} → Stripe Billing through stripe-java with the payments key (with {@code STRIPE_API_BASE} it talks to
 * stripe-mock); {@code local} → the in-process fake. Staging and prod refuse {@code local}: members must be billed.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlusBillingProperties.class)
class PlusBillingConfig {

    @Bean
    MembershipBilling membershipBilling(
            PlusBillingProperties plus, PaymentsProperties payments, Environment environment, Clock clock) {
        return switch (plus.provider()) {
            case LOCAL -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("PLUS_BILLING_PROVIDER=local (fake Plus billing) is refused under"
                            + " staging and prod: set PLUS_BILLING_PROVIDER=stripe (docs/runbooks/stripe.md § Plus).");
                }
                log.info("Plus: local fake billing (PLUS_BILLING_PROVIDER=local) — nothing is charged.");
                yield new LocalMembershipBilling(clock);
            }
            case STRIPE -> {
                var key = payments.stripeSecretKey();
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("PLUS_BILLING_PROVIDER=stripe needs STRIPE_SECRET_KEY.");
                }
                plus.price("monthly");
                plus.price("annual");
                log.info("Plus: Stripe Billing (PLUS_BILLING_PROVIDER=stripe).");
                yield new StripeMembershipBilling(StripeClients.create(key, payments.stripeApiBase()), plus);
            }
        };
    }
}
