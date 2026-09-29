package ca.northline.merchants.integration;

import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.DomainVerifier;
import ca.northline.merchants.application.VerificationGateways.IdentityVerification;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import ca.northline.merchants.application.VerificationGateways.RegistryLookup;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL / TEST ONLY. Deterministic stand-ins for Stripe Identity, the Alberta registries, Stripe Financial Connections
 * and the CNAME check, so the whole onboarding flow can be clicked through without external accounts:
 *
 * <ul>
 *   <li>KYC passes; business registry lookups match; licence numbers of 3+ characters match, anything containing
 *       {@code "manual"} goes to a human (submitted).
 *   <li>Bank linking returns the design's "TD ··3391".
 *   <li>Domains containing {@code "pending"} stay pending, {@code "fail"} fail, everything else verifies.
 * </ul>
 */
@Slf4j
@Component
@Profile({"local", "test"})
class FakeVerificationGateways implements IdentityVerification, RegistryLookup, BankLinking, DomainVerifier {

    @Override
    public Outcome verifyBusinessOwners(String merchantId) {
        log.info("FAKE Stripe Identity: owners of {} passed", merchantId);
        return new Outcome(true, "passed");
    }

    @Override
    public Outcome business(String legalName, String structure, String registryRef) {
        return new Outcome(true, registryRef.isBlank() ? "matched" : registryRef);
    }

    @Override
    public Outcome licence(String registry, String number) {
        var manual = number.toLowerCase(Locale.ROOT).contains("manual") || number.length() < 3;
        return new Outcome(!manual, number);
    }

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
