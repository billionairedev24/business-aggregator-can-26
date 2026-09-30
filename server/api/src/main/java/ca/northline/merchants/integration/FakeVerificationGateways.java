package ca.northline.merchants.integration;

import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.DomainVerifier;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL / TEST ONLY. Deterministic stand-ins for Stripe Financial Connections and the CNAME check, so the whole onboarding flow can be clicked through without external accounts:
 *
 * <ul>
 *   <li>Bank linking returns the design's "TD ··3391".
 *   <li>Domains containing {@code "pending"} stay pending, {@code "fail"} fail, everything else verifies.
 * </ul>
 */
@Component
@Profile({"local", "test"})
class FakeVerificationGateways implements BankLinking, DomainVerifier {

    @Override
    public Outcome link(String merchantId) {
        return new Outcome(true, "TD ··3391");
    }

    @Override
    public Result check(String domain) {
        if (domain.contains("pending")) {
            return Result.PENDING;
        }
        return domain.contains("fail") ? Result.FAILED : Result.VERIFIED;
    }
}
