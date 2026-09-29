package ca.northline.payments.application;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Sales reports (design 02 Reports): KPIs, gross over time vs the previous period, by listing, where customers come from. */
public interface ViewSalesReport {

    /** 30 d → daily points, 90 d → weekly, 12 mo → monthly (calendar months, the current one partial). */
    enum Period implements CodedEnum {
        D30("30d"),
        D90("90d"),
        M12("12mo");

        private final String code;

        Period(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }

        public static Period of(String code) {
            for (var p : values()) {
                if (p.code.equals(code)) {
                    return p;
                }
            }
            throw ca.northline.shared.RuleViolation.of("period", "format", "Choose 30 d, 90 d or 12 mo.");
        }
    }

    enum Granularity implements CodedEnum {
        DAY,
        WEEK,
        MONTH
    }

    record Point(LocalDate start, long currentCents, long previousCents) {}

    /** {@code name == null} = everything outside the top five. */
    record ListingTotal(@Nullable String name, long grossCents) {}

    /** {@code source}: search | repeat | embed | referral. */
    record SourceShare(String source, int pct) {}

    /**
     * @param grossChangePct vs the previous period of the same length, null when it had no sales
     * @param refundRateBps refunds ÷ gross in basis points (240 = 2.4 %)
     * @param benchmarkRefundRateBps platform average for this kind of business, null when not published
     */
    record Report(
            Period period,
            Instant from,
            Instant to,
            long grossCents,
            @Nullable Integer grossChangePct,
            int count,
            long averageTicketCents,
            @Nullable Integer repeatCustomerPct,
            int refundRateBps,
            @Nullable Integer benchmarkRefundRateBps,
            Granularity granularity,
            List<Point> series,
            List<ListingTotal> byListing,
            List<SourceShare> sources) {}

    Report report(String merchantId, Period period);

    /** One line per job / order of the period (Export CSV). */
    String exportCsv(String merchantId, Period period);

    /** "Tax summary (GST)" / "2026 GST collected summary (YTD)". */
    String gstSummaryCsv(String merchantId, int year);

    /** "2025 annual statement". */
    String annualStatementCsv(String merchantId, int year);
}
