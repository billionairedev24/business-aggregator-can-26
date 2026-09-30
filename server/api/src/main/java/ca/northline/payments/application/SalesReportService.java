package ca.northline.payments.application;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.application.SalesReadModel.Sale;
import ca.northline.payments.domain.Zones;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sales reports, the CSV export and the tax documents. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SalesReportService implements ViewSalesReport {

    static final List<String> SOURCES = List.of("search", "repeat", "embed", "referral");
    private static final int TOP_LISTINGS = 5;

    private final SalesReadModel sales;
    private final Clock clock;

    /** A period's window: {@code [from, to)} and the same-length window before it. */
    record Window(Instant from, Instant to, Instant previousFrom, Granularity granularity, List<LocalDate> starts) {}

    Window window(Period period) {
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, Zones.EDMONTON);
        return switch (period) {
            case D30, D90 -> {
                var days = period == Period.D30 ? 30 : 90;
                var first = today.minusDays(days - 1L);
                var step = period == Period.D30 ? 1 : 7;
                var starts = new ArrayList<LocalDate>();
                for (var d = first; !d.isAfter(today); d = d.plusDays(step)) {
                    starts.add(d);
                }
                var from = first.atStartOfDay(Zones.EDMONTON).toInstant();
                yield new Window(
                        from,
                        now,
                        from.minus(Duration.ofDays(days)),
                        period == Period.D30 ? Granularity.DAY : Granularity.WEEK,
                        starts);
            }
            case M12 -> {
                var first = YearMonth.from(today).minusMonths(11).atDay(1);
                var starts = new ArrayList<LocalDate>();
                for (int i = 0; i < 12; i++) {
                    starts.add(first.plusMonths(i));
                }
                yield new Window(
                        first.atStartOfDay(Zones.EDMONTON).toInstant(),
                        now,
                        first.minusMonths(12).atStartOfDay(Zones.EDMONTON).toInstant(),
                        Granularity.MONTH,
                        starts);
            }
        };
    }

    @Override
    public Report report(String merchantId, Period period) {
        var w = window(period);
        var all = sales.sales(merchantId, w.previousFrom(), w.to());
        var current =
                all.stream().filter(s -> !s.occurredAt().isBefore(w.from())).toList();
        var previous =
                all.stream().filter(s -> s.occurredAt().isBefore(w.from())).toList();
        var gross = sum(current);
        var previousGross = sum(previous);
        var count = current.size();
        var customers = sales.customers(merchantId, w.from(), w.to());
        var refunded = sales.refunded(merchantId, w.from(), w.to());
        return new Report(
                period,
                w.from(),
                w.to(),
                gross,
                previousGross == 0 ? null : (int) Math.round((gross - previousGross) * 100.0 / previousGross),
                count,
                count == 0 ? 0 : Math.round((double) gross / count),
                customers.distinct() == 0 ? null : (int) Math.round(customers.repeat() * 100.0 / customers.distinct()),
                gross == 0 ? 0 : (int) Math.round(refunded * 10_000.0 / gross),
                benchmark(current),
                w.granularity(),
                series(w, current, previous),
                byListing(current),
                sources(current));
    }

    private List<Point> series(Window w, List<Sale> current, List<Sale> previous) {
        var starts = w.starts();
        var cur = new long[starts.size()];
        var prev = new long[starts.size()];
        var prevOffset = Duration.between(w.previousFrom(), w.from());
        current.forEach(s -> add(cur, bucket(w, starts, s.occurredAt()), s.amountCents()));
        previous.forEach(s -> add(prev, bucket(w, starts, s.occurredAt().plus(prevOffset)), s.amountCents()));
        var points = new ArrayList<Point>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            points.add(new Point(starts.get(i), cur[i], prev[i]));
        }
        return points;
    }

    private static int bucket(Window w, List<LocalDate> starts, Instant at) {
        var day = LocalDate.ofInstant(at, Zones.EDMONTON);
        if (w.granularity() == Granularity.MONTH) {
            var month = YearMonth.from(day);
            for (int i = 0; i < starts.size(); i++) {
                if (YearMonth.from(starts.get(i)).equals(month)) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = starts.size() - 1; i >= 0; i--) {
            if (!day.isBefore(starts.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void add(long[] into, int i, long cents) {
        if (i >= 0 && i < into.length) {
            into[i] += cents;
        }
    }

    private static List<ListingTotal> byListing(List<Sale> current) {
        Map<String, Long> totals = current.stream()
                .collect(Collectors.groupingBy(
                        s -> Objects.requireNonNullElse(s.listingName(), s.label()),
                        LinkedHashMap::new,
                        Collectors.summingLong(Sale::amountCents)));
        var sorted = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .toList();
        var out = new ArrayList<ListingTotal>();
        if (sorted.size() <= TOP_LISTINGS + 1) {
            sorted.forEach(e -> out.add(new ListingTotal(e.getKey(), e.getValue())));
            return out;
        }
        sorted.stream().limit(TOP_LISTINGS).forEach(e -> out.add(new ListingTotal(e.getKey(), e.getValue())));
        out.add(new ListingTotal(
                null,
                sorted.stream()
                        .skip(TOP_LISTINGS)
                        .mapToLong(Map.Entry::getValue)
                        .sum()));
        return out;
    }

    private static List<SourceShare> sources(List<Sale> current) {
        var counts = current.stream()
                .map(s -> Objects.requireNonNullElse(s.source(), "search"))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        var total = counts.values().stream().mapToLong(Long::longValue).sum();
        return SOURCES.stream()
                .map(src -> new SourceShare(
                        src, total == 0 ? 0 : (int) Math.round(counts.getOrDefault(src, 0L) * 100.0 / total)))
                .toList();
    }

    /** The platform average for the merchant's main kind of business. */
    private @Nullable Integer benchmark(List<Sale> current) {
        var byKind = new EnumMap<EscrowKind, Long>(EscrowKind.class);
        current.forEach(s -> byKind.merge(s.kind(), s.amountCents(), Long::sum));
        var main = byKind.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(EscrowKind.SERVICE);
        var bps = sales.benchmarkRefundBps(main);
        return bps.isPresent() ? bps.getAsInt() : null;
    }

    private static long sum(List<Sale> list) {
        return list.stream().mapToLong(Sale::amountCents).sum();
    }

    // ── CSV ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String exportCsv(String merchantId, Period period) {
        var w = window(period);
        var csv = new Csv(
                "Date", "Job / order", "Customer", "Listing", "Source", "Gross", "Fee", "Net", "GST/HST", "State");
        for (var s : sales.sales(merchantId, w.from(), w.to())) {
            csv.row(
                    DAY.format(s.occurredAt().atZone(Zones.EDMONTON)),
                    s.orderNumber() == null ? s.label() : s.label() + " · " + s.orderNumber(),
                    Objects.requireNonNullElse(s.customerName(), ""),
                    Objects.requireNonNullElse(s.listingName(), ""),
                    Objects.requireNonNullElse(s.source(), ""),
                    dollars(s.amountCents()),
                    dollars(s.feeCents()),
                    dollars(s.amountCents() - s.feeCents()),
                    dollars(s.taxCents()),
                    s.state().code());
        }
        return csv.toString();
    }

    @Override
    public String gstSummaryCsv(String merchantId, int year) {
        var csv = new Csv("Month", "Taxable sales", "GST/HST collected", "GST/HST refunded", "Remitted by Northline");
        long sales = 0, tax = 0, refunded = 0;
        for (var m : months(merchantId, year)) {
            csv.row(
                    m.month().toString(),
                    dollars(m.grossCents()),
                    dollars(m.taxCents()),
                    dollars(m.taxRefundedCents()),
                    dollars(m.taxCents() - m.taxRefundedCents()));
            sales += m.grossCents();
            tax += m.taxCents();
            refunded += m.taxRefundedCents();
        }
        csv.row("Total " + year, dollars(sales), dollars(tax), dollars(refunded), dollars(tax - refunded));
        csv.note(
                "Northline collects and remits GST/HST on your sales as the marketplace facilitator; keep this summary with your records.");
        return csv.toString();
    }

    @Override
    public String annualStatementCsv(String merchantId, int year) {
        var csv = new Csv("Month", "Gross sales", "Northline fees", "Net earnings", "Refunds", "Paid out");
        long g = 0, f = 0, r = 0, p = 0;
        for (var m : months(merchantId, year)) {
            csv.row(
                    m.month().toString(),
                    dollars(m.grossCents()),
                    dollars(m.feeCents()),
                    dollars(m.grossCents() - m.feeCents()),
                    dollars(m.refundedCents()),
                    dollars(m.paidOutCents()));
            g += m.grossCents();
            f += m.feeCents();
            r += m.refundedCents();
            p += m.paidOutCents();
        }
        csv.row("Total " + year, dollars(g), dollars(f), dollars(g - f), dollars(r), dollars(p));
        return csv.toString();
    }

    /** Every month of the year up to the current one, zero-filled. */
    private List<SalesReadModel.Month> months(String merchantId, int year) {
        var current = YearMonth.from(LocalDate.ofInstant(clock.instant(), Zones.EDMONTON));
        var found = sales.months(merchantId, year).stream()
                .collect(Collectors.toMap(SalesReadModel.Month::month, Function.identity()));
        var out = new ArrayList<SalesReadModel.Month>();
        for (int m = 1; m <= 12; m++) {
            var ym = YearMonth.of(year, m);
            if (ym.isAfter(current)) {
                break;
            }
            out.add(found.getOrDefault(ym, new SalesReadModel.Month(ym, 0, 0, 0, 0, 0, 0)));
        }
        return out;
    }

    static String dollars(long cents) {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /** RFC 4180 CSV with a header row. */
    static final class Csv {
        private final StringBuilder out = new StringBuilder();

        Csv(String... header) {
            row(header);
        }

        void row(String... cells) {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                var c = cells[i];
                out.append(
                        c.contains(",") || c.contains("\"") || c.contains("\n")
                                ? '"' + c.replace("\"", "\"\"") + '"'
                                : c);
            }
            out.append("\r\n");
        }

        void note(String text) {
            out.append("\r\n");
            row(text);
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
