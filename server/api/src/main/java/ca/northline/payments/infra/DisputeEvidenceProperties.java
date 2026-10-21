package ca.northline.payments.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.payments.dispute-evidence.*} (S-156, docs/runbooks/stripe.md § Card disputes).
 *
 * @param provider {@code DISPUTE_EVIDENCE_PROVIDER}: {@code local} (the in-process fake; refused under staging and
 *     prod) or {@code stripe} (Stripe's Files and Disputes APIs with {@code STRIPE_SECRET_KEY})
 */
@ConfigurationProperties("northline.payments.dispute-evidence")
record DisputeEvidenceProperties(@DefaultValue("local") Provider provider) {

    enum Provider {
        LOCAL,
        STRIPE
    }
}
