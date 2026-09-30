package ca.northline.merchants.application;

/**
 * Outbound ports to the external systems behind the verification checklist. Each has a fake adapter under the
 * {@code local} and {@code test} profiles ({@code merchants.integration}); production adapters (Stripe Financial
 * Connections, custom domains) plug in behind the same interfaces. Stripe Identity ({@link IdentityVerification}, S-22)
 * and the business registries ({@link BusinessRegistry}, S-23) have their own ports.
 */
public final class VerificationGateways {
    private VerificationGateways() {}

    /** Outcome of an external check: {@code verified} = confirmed now, otherwise queued for a human. */
    public record Outcome(boolean verified, String reference) {}

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
