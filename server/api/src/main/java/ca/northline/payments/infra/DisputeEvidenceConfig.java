package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceGateway;
import ca.northline.shared.stripe.StripeClients;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Picks the card-dispute evidence adapter by {@code northline.payments.dispute-evidence.provider}
 * ({@code DISPUTE_EVIDENCE_PROVIDER}, S-156): {@code stripe} → stripe-java with the payments key (with
 * {@code STRIPE_API_BASE} it talks to stripe-mock); {@code local} → the in-process fake. Staging and prod refuse
 * {@code local}: evidence an agent submits must reach the issuer.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DisputeEvidenceProperties.class)
class DisputeEvidenceConfig {

    @Bean
    DisputeEvidenceGateway disputeEvidenceGateway(
            DisputeEvidenceProperties evidence, PaymentsProperties payments, Environment environment, Clock clock) {
        return switch (evidence.provider()) {
            case LOCAL -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("DISPUTE_EVIDENCE_PROVIDER=local (fake card-dispute evidence) is"
                            + " refused under staging and prod: set DISPUTE_EVIDENCE_PROVIDER=stripe"
                            + " (docs/runbooks/stripe.md § Card disputes).");
                }
                log.info("Card disputes: local fake evidence gateway (DISPUTE_EVIDENCE_PROVIDER=local) — nothing is"
                        + " sent to Stripe.");
                yield new LocalDisputeEvidenceGateway(clock);
            }
            case STRIPE -> {
                var key = payments.stripeSecretKey();
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("DISPUTE_EVIDENCE_PROVIDER=stripe needs STRIPE_SECRET_KEY.");
                }
                log.info("Card disputes: evidence goes to Stripe (DISPUTE_EVIDENCE_PROVIDER=stripe).");
                yield new StripeDisputeEvidenceGateway(StripeClients.create(key, payments.stripeApiBase()));
            }
        };
    }
}
