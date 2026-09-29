package ca.northline.merchants.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Compliance items that need the owner (Dashboard › Needs you). Added by the operations workstream as a minimal read
 * over {@code merchants.verifications}; the settings/compliance workstream owns it and may replace the adapter.
 */
public interface ComplianceStatus {

    /** Verifications that are expired or still to do, most urgent (earliest expiry) first. */
    List<DueItem> dueItems(String merchantId);

    /**
     * @param checkType {@code merchants.verifications.check_type} ({@code wcb}, {@code insurance}, …)
     * @param status {@code expired} or {@code todo}
     */
    record DueItem(
            String checkType,
            @Nullable String registry,
            String status,
            @Nullable Instant expiresAt) {}
}
