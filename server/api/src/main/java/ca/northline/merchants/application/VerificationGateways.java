package ca.northline.merchants.application;

/**
 * Outbound ports to the external systems behind the verification checklist. Each has a fake adapter under the
 * {@code local} and {@code test} profiles ({@code merchants.integration}); production adapters (Stripe Identity /
 * Connect, Alberta registries, Stripe Financial Connections) plug in behind the same interfaces.
 */
public final class VerificationGateways {
    private VerificationGateways() {}

    /** Outcome of an external check: {@code verified} = confirmed now, otherwise queued for a human. */
    public record Outcome(boolean verified, String reference) {}

    /** Stripe Identity / Connect KYC for every principal over the threshold. */
    public interface IdentityVerification {
        Outcome verifyBusinessOwners(String merchantId);
    }

    /** Provincial / federal registries (corporate registry, AMVIC, RECA, AHS permits, AGLC, CRA). */
    public interface RegistryLookup {
        Outcome business(String legalName, String structure, String registryRef);

        Outcome licence(String registry, String number);
    }

    /** Payout bank account (Stripe Financial Connections or void cheque). */
    public interface BankLinking {
        Outcome link(String merchantId);
    }

    /** DNS check that a custom domain CNAMEs to pages.northline.ca. */
    public interface DomainVerifier {
        enum Result {
            VERIFIED,
            PENDING,
            FAILED
        }

        Result check(String domain);
    }
}
