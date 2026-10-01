package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Earnings (headline, KPIs, ledger) and Sales reports (KPIs, series, CSV, tax documents). */
@Import(PaymentsFixture.class)
class EarningsApiTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    PaymentsFixture.Shop shop;

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        var m = shop.merchantId();
        // released since the last payout → balance 224.77 + 71.89 = 296.66
        fx.escrow(m, "goods", "released", 24_700, 900, "Brake pads", "D. Kowalski", hoursAgo(80), hoursAgo(20));
        fx.escrow(m, "service", "released", 7_900, 900, "Oil & filter", "S. Bouchard", hoursAgo(70), hoursAgo(22));
        // held: 109.20 + 72.80 (net), releasing in 31 h and 20 h
        fx.escrow(m, "service", "held", 12_000, 900, "Diagnostic", "M. Tran", hoursAgo(17), inHours(31));
        fx.escrow(m, "service", "held", 8_000, 900, "Tire swap", "S. Bouchard", hoursAgo(28), inHours(20));
        // on hold: disputed 160.00
        var disputed =
                fx.escrow(m, "service", "held", 16_000, 900, "Pre-purchase", "A. Osei", hoursAgo(120), inHours(1));
        fx.dispute(m, disputed, 16_000, PaymentsFixture.caseNumber("DS"));
    }

    @Test
    void overview_headlineKpisAndTakeRates() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/earnings", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCents").value(29_666))
                .andExpect(jsonPath("$.escrowNetCents").value(10_920 + 7_280))
                .andExpect(jsonPath("$.escrowCount").value(2))
                .andExpect(jsonPath("$.onHoldCents").value(16_000))
                .andExpect(jsonPath("$.onHoldDisputes").value(1))
                .andExpect(jsonPath("$.frequency").value("weekly"))
                .andExpect(jsonPath("$.tier").value("master"))
                .andExpect(jsonPath("$.takeRateBps").value(900))
                .andExpect(jsonPath("$.takeRates.trusted").value(1200))
                .andExpect(jsonPath("$.takeRates.registered").value(1500))
                .andExpect(jsonPath("$.nextPayoutAt").exists());
    }

    @Test
    void ledger_newestFirst_withNetAndState() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/earnings/ledger", shop.merchantId())
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].label").value("Diagnostic"))
                .andExpect(jsonPath("$.items[0].grossCents").value(12_000))
                .andExpect(jsonPath("$.items[0].feeCents").value(1_080))
                .andExpect(jsonPath("$.items[0].netCents").value(10_920))
                .andExpect(jsonPath("$.items[0].state").value("held"))
                .andExpect(jsonPath("$.items[4].state").value("disputed"));
    }

    @Test
    void bookkeeperReads_technicianDoesNot() throws Exception {
        var bookkeeper = fx.member(shop, MerchantRole.BOOKKEEPER);
        var technician = fx.member(shop, MerchantRole.TECHNICIAN);
        mvc.perform(get("/api/v1/merchants/{id}/earnings", shop.merchantId()).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/merchants/{id}/earnings", shop.merchantId()).with(TestJwt.member(technician)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
    }

    @Test
    void nonMemberAndMissingMfa_areForbidden() throws Exception {
        var stranger = data.user("Stranger");
        mvc.perform(get("/api/v1/merchants/{id}/earnings", shop.merchantId()).with(TestJwt.member(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/reports", shop.merchantId())
                        .with(TestJwt.memberWithoutMfa(shop.ownerId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void report_kpisSeriesListingsAndSources() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/reports", shop.merchantId())
                        .param("period", "30d")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("30d"))
                .andExpect(jsonPath("$.grossCents").value(24_700 + 7_900 + 12_000 + 8_000 + 16_000))
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.averageTicketCents").value(13_720))
                .andExpect(jsonPath("$.repeatCustomerPct").value(25)) // S. Bouchard twice, 4 customers
                .andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.series", hasSize(30)))
                .andExpect(jsonPath("$.byListing[0].name").value("Brake pads"))
                .andExpect(jsonPath("$.sources[0].source").value("search"))
                .andExpect(jsonPath("$.sources[0].pct").value(100))
                .andExpect(jsonPath("$.benchmarkRefundRateBps").value(310));
        mvc.perform(get("/api/v1/merchants/{id}/reports", shop.merchantId())
                        .param("period", "12mo")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(jsonPath("$.granularity").value("month"))
                .andExpect(jsonPath("$.series", hasSize(12)));
    }

    @Test
    void report_unknownPeriod_is422() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/reports", shop.merchantId())
                        .param("period", "7d")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("period"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose 30 d, 90 d or 12 mo."));
    }

    @Test
    void exportAndTaxDocuments_areCsvDownloads() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/reports/export.csv", shop.merchantId())
                        .param("period", "90d")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("northline-sales-90d-")))
                .andExpect(content().string(startsWith("Date,Job / order,Customer,Listing,Source,Gross,Fee,Net")))
                .andExpect(content()
                        .string(containsString("Brake pads,D. Kowalski,Brake pads,search,247.00,22.23,224.77")));
        mvc.perform(get("/api/v1/merchants/{id}/reports/gst-summary.csv", shop.merchantId())
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(startsWith(
                                "Month,Taxable sales,GST/HST collected,GST/HST refunded,Remitted by Northline")))
                .andExpect(content().string(containsString("marketplace facilitator")));
        mvc.perform(get("/api/v1/merchants/{id}/reports/annual-statement.csv", shop.merchantId())
                        .param("year", "2025")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Total 2025,0.00,0.00,0.00,0.00,0.00")));
    }

    /** S-41: the tax documents as PDF statements, in either language, for the business's own province. */
    @Test
    void taxDocumentsArePdfStatements_inEnglishAndFrench() throws Exception {
        jdbc.sql("update merchants.merchants set province = 'NS', legal_name = 'Prairie Wrench Mobile Mechanics Ltd.',"
                        + " gst_number = '123456789 RT0001' where id = ?")
                .param(shop.merchantId())
                .update();
        var en = mvc.perform(get("/api/v1/merchants/{id}/reports/gst-summary.pdf", shop.merchantId())
                        .param("year", "2025")
                        .param("lang", "en")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("northline-gst-summary-2025.pdf")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(en, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdfText(en))
                .contains("2025 GST/HST collected summary")
                .contains("Prairie Wrench Mobile Mechanics Ltd.")
                .contains("Operating as Prairie Wrench")
                .contains("GST/HST number: 123456789 RT0001")
                .contains("Province: Nova Scotia")
                .contains("Sales-tax rate in Nova Scotia:")
                .contains("January 2025")
                .contains("Total 2025 $0.00")
                .contains("marketplace facilitator");

        var fr = mvc.perform(get("/api/v1/merchants/{id}/reports/annual-statement.pdf", shop.merchantId())
                        .param("year", "2025")
                        .header("Accept-Language", "fr-CA")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("northline-annual-statement-2025-fr.pdf")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(pdfText(fr))
                .contains("Relevé annuel 2025")
                .contains("Province : Nouvelle-Écosse")
                .contains("Janvier 2025")
                .contains("Frais Northline")
                .containsPattern("0,00[\\s\\u00a0]\\$");

        var gstFr = mvc.perform(get("/api/v1/merchants/{id}/reports/gst-summary.pdf", shop.merchantId())
                        .param("year", "2025")
                        .param("lang", "fr")
                        .with(TestJwt.member(shop.ownerId())))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(pdfText(gstFr)).contains("Taux de taxe de vente en Nouvelle-Écosse").contains("No de TPS/TVH");

        // finance only: a technician can't download it
        mvc.perform(get("/api/v1/merchants/{id}/reports/gst-summary.pdf", shop.merchantId())
                        .with(TestJwt.member(fx.member(shop, MerchantRole.TECHNICIAN))))
                .andExpect(status().isForbidden());
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("[ \t]+", " ");
        }
    }
}
