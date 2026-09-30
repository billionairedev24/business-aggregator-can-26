package ca.northline.merchants.application;

import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.merchants.domain.MerchantType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: the compliance ledger over {@code merchants.verifications} and the facts the screen names. */
public interface ComplianceLedgerStore {

    /** Ledger rows as of {@code now}, in checklist order; Stripe-side checks (KYC, bank, MFA, visit) excluded. */
    List<ComplianceItem> items(String merchantId, Instant now);

    /**
     * Checklist rows kept outside the ledger's list that still feed {@code ComplianceStatus}: the owners' identity
     * verification ({@code kyc}, S-22).
     */
    List<ComplianceItem> platformChecks(String merchantId, Instant now);

    Optional<ComplianceItem> item(String merchantId, String verificationId, Instant now);

    /** A renewal was handed in: status {@code submitted}, the document becomes the row's evidence. */
    void submitRenewal(String verificationId, String documentId, String actorId, Instant at);

    Optional<BusinessFacts> facts(String merchantId);

    void linkStripeAccount(String merchantId, String accountId);

    Optional<Acceptance> latestAcceptance(String merchantId);

    void accept(String merchantId, String version, String actorId, Instant at);

    /**
     * @param requiredFor the business's first approved category name ("Mobile mechanic")
     * @param ownerName the principal Stripe verifies first (largest share)
     */
    record BusinessFacts(
            MerchantType type,
            String displayName,
            String legalName,
            @Nullable String businessNumber,
            @Nullable String province,
            @Nullable String requiredFor,
            @Nullable String ownerName,
            @Nullable Integer takeRateBps,
            @Nullable String stripeAccountId,
            @Nullable String status) {}

    record Acceptance(String version, Instant acceptedAt) {}
}
