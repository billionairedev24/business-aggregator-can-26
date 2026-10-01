package ca.northline.payments.application;

import ca.northline.payments.api.MerchantBillingFacts;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.TaxRates;
import ca.northline.shared.Bytes;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaxStatements}: the same months and sums as the CSVs ({@link SalesReportService#yearMonths}), labelled in the
 * caller's language (en-CA / fr-CA: month names, $1,234.56 / 1 234,56 $), headed with the business's legal name, GST/HST
 * number and province — from the merchants module and the region model, so any province reads right.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TaxStatementService implements TaxStatements {

    private final SalesReadModel sales;
    private final Clock clock;
    private final BusinessTime time;
    private final MerchantPlaces places;
    private final TaxRates rates;
    private final MerchantBillingFacts merchants;
    private final StatementRenderer renderer;

    @Override
    public Bytes gstSummaryPdf(String merchantId, int year, Locale locale) {
        var l = Labels.of(locale);
        var rows = new ArrayList<List<String>>();
        long sold = 0, tax = 0, refunded = 0;
        for (var m : months(merchantId, year)) {
            rows.add(List.of(
                    l.month(m.month()),
                    l.money(m.grossCents()),
                    l.money(m.taxCents()),
                    l.money(m.taxRefundedCents()),
                    l.money(m.taxCents() - m.taxRefundedCents())));
            sold += m.grossCents();
            tax += m.taxCents();
            refunded += m.taxRefundedCents();
        }
        var place = places.of(merchantId);
        var party = party(merchantId, l, place);
        var province = place.province();
        if (province != null) {
            var profile = place.profile();
            var in = profile != null ? profile.nameIn(l.locale) : (l.fr ? "en " : "in ") + place.provinceName(l.locale);
            party.add((l.fr ? "Taux de taxe de vente " : "Sales-tax rate ")
                    + in
                    + (l.fr ? " : " : ": ")
                    + l.percent(rates.bpsFor(province)));
        }
        return renderer.pdf(new StatementDocument(
                l.tag,
                l.fr ? "Sommaire de la TPS/TVH perçue " + year : year + " GST/HST collected summary",
                party,
                l.fr
                        ? List.of(
                                "Mois",
                                "Ventes taxables",
                                "TPS/TVH perçue",
                                "TPS/TVH remboursée",
                                "Remise par Northline")
                        : List.of(
                                "Month",
                                "Taxable sales",
                                "GST/HST collected",
                                "GST/HST refunded",
                                "Remitted by Northline"),
                rows,
                List.of("Total " + year, l.money(sold), l.money(tax), l.money(refunded), l.money(tax - refunded)),
                List.of(
                        l.fr
                                ? "Northline perçoit et remet la TPS/TVH sur vos ventes à titre de facilitateur de marché; conservez ce sommaire avec vos dossiers."
                                : "Northline collects and remits GST/HST on your sales as the marketplace facilitator; keep this summary with your records."),
                footer(merchantId, l)));
    }

    @Override
    public Bytes annualStatementPdf(String merchantId, int year, Locale locale) {
        var l = Labels.of(locale);
        var rows = new ArrayList<List<String>>();
        long g = 0, f = 0, r = 0, p = 0;
        for (var m : months(merchantId, year)) {
            rows.add(List.of(
                    l.month(m.month()),
                    l.money(m.grossCents()),
                    l.money(m.feeCents()),
                    l.money(m.grossCents() - m.feeCents()),
                    l.money(m.refundedCents()),
                    l.money(m.paidOutCents())));
            g += m.grossCents();
            f += m.feeCents();
            r += m.refundedCents();
            p += m.paidOutCents();
        }
        return renderer.pdf(new StatementDocument(
                l.tag,
                l.fr ? "Relevé annuel " + year : year + " annual statement",
                party(merchantId, l, places.of(merchantId)),
                l.fr
                        ? List.of("Mois", "Ventes brutes", "Frais Northline", "Revenus nets", "Remboursements", "Versé")
                        : List.of("Month", "Gross sales", "Northline fees", "Net earnings", "Refunds", "Paid out"),
                rows,
                List.of("Total " + year, l.money(g), l.money(f), l.money(g - f), l.money(r), l.money(p)),
                List.of(
                        l.fr
                                ? "Montants en dollars canadiens, par mois de la vente, dans le fuseau horaire de votre entreprise."
                                : "Amounts in Canadian dollars, by month of sale, in your business's time zone."),
                footer(merchantId, l)));
    }

    private List<SalesReadModel.Month> months(String merchantId, int year) {
        return SalesReportService.yearMonths(sales, merchantId, year, time.of(merchantId), clock.instant());
    }

    /** Legal name, trading name when it differs, GST/HST number, province. */
    private List<String> party(String merchantId, Labels l, MerchantPlaces.MerchantPlace place) {
        var out = new ArrayList<String>();
        merchants.statementParty(merchantId).ifPresent(p -> {
            out.add(p.legalName());
            if (!p.displayName().equalsIgnoreCase(p.legalName())) {
                out.add((l.fr ? "Faisant affaire sous le nom de " : "Operating as ") + p.displayName());
            }
            var gst = p.gstNumber();
            if (gst != null) {
                out.add((l.fr ? "No de TPS/TVH : " : "GST/HST number: ") + gst);
            }
        });
        if (place.province() != null) {
            out.add((l.fr ? "Province : " : "Province: ") + place.provinceName(l.locale));
        }
        return out;
    }

    private String footer(String merchantId, Labels l) {
        var today = LocalDate.ofInstant(clock.instant(), time.of(merchantId));
        var date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(l.locale)
                .format(today);
        return (l.fr ? "Produit le " : "Generated ") + date + " · Northline";
    }

    /** Language-specific formats. PDF standard fonts have no narrow no-break space, so it becomes a no-break space. */
    private record Labels(boolean fr, Locale locale, String tag, NumberFormat currency) {

        static Labels of(Locale locale) {
            var fr = "fr".equals(locale.getLanguage());
            var l = fr ? Locale.CANADA_FRENCH : Locale.CANADA;
            return new Labels(fr, l, fr ? "fr-CA" : "en-CA", NumberFormat.getCurrencyInstance(l));
        }

        String money(long cents) {
            return currency.format(BigDecimal.valueOf(cents, 2)).replace(' ', ' ');
        }

        String month(YearMonth month) {
            var name = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        String percent(int bps) {
            var pct = BigDecimal.valueOf(bps, 2).stripTrailingZeros().toPlainString();
            return (fr ? pct.replace('.', ',') + " %" : pct + " %");
        }
    }
}
