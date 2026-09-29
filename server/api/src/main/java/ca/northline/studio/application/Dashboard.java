package ca.northline.studio.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything the Studio dashboard shows, for every portal: the client picks the provider, seller or "both" variant
 * (headline, KPIs, "Today" vs "Tonight's run", "Needs you"). Money in CAD cents; instants ISO-8601; the client formats
 * in America/Edmonton. Sections whose owning module has no data yet are empty / null.
 */
public record Dashboard(
        LocalDate today,
        List<TodayJob> jobsToday,
        List<RunOrder> run,
        Counts counts,
        Earnings earnings,
        Reputation reputation,
        List<OpenCase> cases,
        List<LowStock> lowStock,
        List<ComplianceItem> compliance,
        Coaching coaching) {

    /**
     * A visit today. {@code mine} = assigned to the viewer (otherwise the design shows the member's first name instead
     * of the state).
     */
    public record TodayJob(
            String id,
            Instant startsAt,
            String title,
            @Nullable String customerName,
            @Nullable String area,
            @Nullable String access,
            @Nullable Long escrowHeldCents,
            String state,
            @Nullable String memberName,
            boolean mine) {}

    public record RunOrder(
            String orderId,
            @Nullable String ref,
            @Nullable String runLabel,
            @Nullable String customerName,
            @Nullable String area,
            List<Item> items,
            boolean packed) {}

    public record Item(String title, int qty) {}

    /**
     * @param quoteRespondBy earliest respond-by among open quote requests ("respond within 1 h 12 m")
     * @param runCutoff earliest upcoming cut-off among orders to pack ("run R-611 closes 5:45 pm")
     */
    public record Counts(
            int visitsToday,
            int quoteRequestsOpen,
            @Nullable Instant quoteRespondBy,
            int toPack,
            @Nullable Instant runCutoff,
            @Nullable String runLabel,
            long jobsThisMonth,
            long ordersThisMonth,
            long itemsThisMonth) {}

    public record Earnings(
            long netThisMonthCents,
            long netLastMonthCents,
            @Nullable Long releasingCents,
            @Nullable Instant releasingAt,
            List<Week> weeks) {}

    public record Week(LocalDate start, long servicesCents, long partsCents) {}

    /**
     * @param quality components as percentages: {@code on_time}, {@code photos}, {@code response}, {@code rebook},
     *     {@code dispute_rate}
     */
    public record Reputation(
            @Nullable BigDecimal rating,
            long reviews,
            @Nullable Integer qualityScore,
            Map<String, BigDecimal> quality,
            @Nullable Integer refundRateBps) {}

    /** A dispute or refund case waiting for the merchant; {@code subject} = job title or refund reason. */
    public record OpenCase(
            String kind,
            @Nullable String customerName,
            @Nullable String subject) {}

    public record LowStock(String name, int stock) {}

    /** {@code pausesAt}: instant book / ordering pauses 15 days after an expiry (docs/DECISIONS.md). */
    public record ComplianceItem(
            String checkType,
            @Nullable String registry,
            String status,
            @Nullable Instant expiresAt,
            @Nullable Instant pausesAt) {}

    /** "completion photos missing on 3 of your last 20 jobs … Next tier review Oct 1." */
    public record Coaching(int jobs, int withoutPhotos, LocalDate nextTierReview) {}
}
