package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Money figures for the Studio dashboard, all CAD cents net of Northline's fee. Added by the operations workstream as
 * a read over {@code payments.*}; the finance workstream owns it and may replace the adapter.
 */
public interface EarningsSummary {

    /** Net transferred to the merchant in [from, to). */
    long netBetween(String merchantId, Instant from, Instant to);

    /** Net per week for the {@code starts.size()} weeks starting at each instant (7 days each), split services/parts. */
    List<WeekNet> weeklyNet(String merchantId, List<Instant> starts);

    /** The next payout that has not arrived yet. */
    Optional<Release> nextRelease(String merchantId, Instant now);

    /** Open disputes and refund cases waiting for the merchant, oldest first. */
    List<OpenCase> openCases(String merchantId);

    /** Refunded share of goods sales in [from, to) in basis points, or empty without sales. */
    Optional<Integer> refundRateBps(String merchantId, Instant from, Instant to);

    record WeekNet(Instant start, long servicesCents, long partsCents) {}

    record Release(long amountCents, Instant arrivesAt) {}

    /**
     * @param kind {@code dispute} or {@code refund}
     * @param refType {@code booking} or {@code order_line}
     */
    record OpenCase(
            String kind,
            String id,
            String refType,
            String refId,
            @Nullable String customerId,
            @Nullable String reason,
            @Nullable String customerName) {}
}
