package ca.northline.account.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** "Something's wrong" (S-60): what can be reported on an order, food order or booking, and the report. */
public final class Problems {
    private Problems() {}

    /**
     * One thing that can be reported.
     *
     * @param ref the order line, or the booking
     * @param amountCents what was paid for it, before tax
     * @param taxCents its tax (asked back with it)
     * @param status {@code open | reported | closed | not_yet | not_paid}
     * @param reportBy until when it can be reported (the escrow window's end), when open
     */
    public record Item(
            String ref,
            String title,
            int qty,
            long amountCents,
            long taxCents,
            String merchantId,
            String merchantName,
            String status,
            @Nullable Instant reportBy) {}

    /**
     * @param kind {@code order | food | booking}
     * @param status the best of its items' statuses ({@code open} when anything can be reported)
     * @param card the default card refunds go back to ("Visa ··4471"), when one is saved
     */
    public record Context(
            String kind,
            String id,
            @Nullable String ref,
            String title,
            Instant date,
            List<Item> items,
            List<String> reasons,
            String status,
            @Nullable Instant reportBy,
            @Nullable Card card) {

        public Context {
            items = List.copyOf(items);
            reasons = List.copyOf(reasons);
        }
    }

    public record Card(String brand, String last4) {}

    /**
     * @param items the order lines (or the booking) reported
     * @param triageCategory / triageSummary S-132's suggestion, when the web asked for one (optional)
     */
    public record Report(
            String kind,
            String id,
            List<String> items,
            String reason,
            @Nullable String note,
            List<String> attachmentIds,
            @Nullable String triageCategory,
            @Nullable String triageSummary) {

        public Report {
            items = List.copyOf(items);
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /** One refund case opened by the report ("RF-2201"). */
    public record Opened(
            String id,
            String number,
            long amountCents,
            long taxCents,
            String merchantName,
            @Nullable Instant respondBy) {}

    /** @param caseCode the staff case ({@code HD-…}) */
    public record Reported(
            String caseId,
            String caseCode,
            List<Opened> refunds,
            Instant submittedAt,
            long totalCents,
            @Nullable Card card) {

        public Reported {
            refunds = List.copyOf(refunds);
        }
    }

    public interface ReportProblems {
        Context context(String userId, String kind, String id);

        Reported report(String userId, Report report, java.util.Locale locale);
    }
}
