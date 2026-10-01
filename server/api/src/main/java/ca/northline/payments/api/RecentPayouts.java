package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;

/**
 * A business's latest payouts for screens outside payments (S-66: the help form's "Related to" list offers them when
 * a merchant opens a case).
 */
public interface RecentPayouts {

    /**
     * @param state {@code pending | in_transit | paid | failed | canceled}
     * @param kind {@code scheduled | instant}
     */
    record PayoutRef(String id, long amountCents, String kind, String state, Instant arrivesAt) {}

    /** Newest first, at most {@code limit}. */
    List<PayoutRef> recent(String merchantId, int limit);
}
