package ca.northline.merchants.application;

/**
 * Outbound ports to the external systems behind the verification checklist. Each has a fake adapter under the
 * {@code local} and {@code test} profiles ({@code merchants.integration}); production adapters (Stripe Financial
 * Connections) plug in behind the same interfaces. Custom domains (S-31) use {@link DnsResolver} and {@link DomainEdge}. Stripe Identity ({@link IdentityVerification}, S-22)
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
}
