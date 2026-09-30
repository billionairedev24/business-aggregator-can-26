package ca.northline.merchants.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Compliance items that need the owner (Dashboard › Needs you, the Stripe &amp; compliance badge). Owned by the settings
 * &amp; compliance workstream; implemented by the merchants module's compliance ledger over
 * {@code merchants.verifications}.
 */
public interface ComplianceStatus {

    /**
     * Verifications that are expired (including verified rows past their expiry), rejected or still to do, most
     * urgent (earliest expiry) first. Rows handed in for review ({@code submitted}) are not due. Besides the ledger's
     * licences, insurance and policies this includes the owners' identity verification ({@code kyc}, S-22): it is due
     * while an owner still has to verify or must try again.
     */
    List<DueItem> dueItems(String merchantId);

    /**
     * @param checkType {@code merchants.verifications.check_type} ({@code wcb}, {@code insurance}, …)
     * @param status {@code expired} or {@code todo} (rejected rows are reported as {@code todo}: a new upload is due)
     * @param pausesAt when instant book / ordering pauses because of it (expiry + grace), null when not expired
     */
    record DueItem(
            String checkType,
            @Nullable String registry,
            String status,
            @Nullable Instant expiresAt,
            @Nullable Instant pausesAt) {}
}
