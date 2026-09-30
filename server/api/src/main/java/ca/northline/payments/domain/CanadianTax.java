package ca.northline.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Canadian sales tax by province of supply: which taxes apply and the jurisdiction code the Studio's tax table groups
 * them under ({@code payments.tax_jurisdiction_totals.jurisdiction}). Stripe Tax does the real calculation; the rates
 * here are the local fake's (fixed, as of 2026) and the fallback labels. Northline is the marketplace facilitator, so
 * every province's tax is Northline's to collect and remit.
 */
public final class CanadianTax {

    private CanadianTax() {}

    /** One tax of a province: {@code gst}, {@code hst}, {@code pst}, {@code qst} or {@code rst}, and its rate in %. */
    public record Component(String taxType, BigDecimal percent) {

        /** Tax on {@code amountCents}, half-up to the cent (Stripe rounds each tax on its own). */
        public long on(long amountCents) {
            return BigDecimal.valueOf(amountCents)
                    .multiply(percent)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValueExact();
        }
    }

    /** One line of a calculation: a tax, its rate and the amount. */
    public record Line(String taxType, BigDecimal percent, long taxCents) {}

    private static Component gst() {
        return new Component("gst", new BigDecimal("5"));
    }

    private static Component hst(String percent) {
        return new Component("hst", new BigDecimal(percent));
    }

    /** Provinces and territories (Canada Post codes, as Stripe's {@code address.state}). */
    public enum Province {
        AB("ab_gst", gst()),
        BC("bc_gst_pst", gst(), new Component("pst", new BigDecimal("7"))),
        MB("mb_gst_pst", gst(), new Component("rst", new BigDecimal("7"))),
        NB("nb_hst", hst("15")),
        NL("nl_hst", hst("15")),
        NS("ns_hst", hst("14")),
        NT("nt_gst", gst()),
        NU("nu_gst", gst()),
        ON("on_hst", hst("13")),
        PE("pe_hst", hst("15")),
        QC("qc_gst_qst", gst(), new Component("qst", new BigDecimal("9.975"))),
        SK("sk_gst_pst", gst(), new Component("pst", new BigDecimal("6"))),
        YT("yt_gst", gst());

        private final String jurisdiction;

        @SuppressWarnings("ImmutableEnumChecker") // List.of: unmodifiable, and Component is a record of immutables
        private final List<Component> components;

        Province(String jurisdiction, Component... components) {
            this.jurisdiction = jurisdiction;
            this.components = List.of(components);
        }

        /** {@code ab_gst}, {@code bc_gst_pst}, {@code on_hst}, … */
        public String jurisdiction() {
            return jurisdiction;
        }

        public List<Component> components() {
            return components;
        }

        /** The fixed-rate calculation (the local fake's; also what a test expects Stripe Tax to answer). */
        public List<Line> lines(long amountCents) {
            return components.stream()
                    .map(c -> new Line(c.taxType(), c.percent(), c.on(amountCents)))
                    .toList();
        }

        public static Optional<Province> of(String code) {
            var upper = code.strip().toUpperCase(Locale.ROOT);
            return Arrays.stream(values()).filter(p -> p.name().equals(upper)).findFirst();
        }
    }

    public static long total(List<Line> lines) {
        return lines.stream().mapToLong(Line::taxCents).sum();
    }

    /** {@code 2026-Q3}: the Edmonton calendar quarter {@code at} falls in (GST/HST reporting periods are quarterly). */
    public static String period(Instant at) {
        var date = at.atZone(Zones.EDMONTON).toLocalDate();
        return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
    }

    /**
     * The tax given back with a refund of {@code refundCents} out of a sale of {@code amountCents} that collected
     * {@code taxCents}: the same share, half-up; a refund of the whole amount gives back exactly the tax collected.
     */
    public static long refundShare(long refundCents, long amountCents, long taxCents) {
        if (refundCents <= 0 || amountCents <= 0 || taxCents <= 0) {
            return 0;
        }
        if (refundCents >= amountCents) {
            return taxCents;
        }
        return BigDecimal.valueOf(taxCents)
                .multiply(BigDecimal.valueOf(refundCents))
                .divide(BigDecimal.valueOf(amountCents), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
