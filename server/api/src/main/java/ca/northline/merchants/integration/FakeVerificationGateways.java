package ca.northline.merchants.integration;

import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL / TEST ONLY. Deterministic stand-in for Stripe Financial Connections, so the whole onboarding flow can be clicked
 * through without external accounts: bank linking returns the design's "TD ··3391". (Custom domains: the in-memory DNS
 * zone {@link FakeDnsResolver}.)
 */
@Component
@Profile({"local", "test"})
class FakeVerificationGateways implements BankLinking {

    @Override
    public Outcome link(String merchantId) {
        return new Outcome(true, "TD ··3391");
    }
}
