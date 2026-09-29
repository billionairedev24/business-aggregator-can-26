package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.merchants.api.VerificationRenewalSubmitted;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Stripe &amp; compliance: {@code /api/v1/merchants/{id}/compliance/**}, the badge and {@link ComplianceStatus}. */
@RecordApplicationEvents
class ComplianceApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ComplianceStatus complianceStatus;

    private record Ledger(String merchantId, String owner, String wcb, String amvic) {}

    /** Provider with an AMVIC licence (also recorded twice), insurance, an expired WCB letter and a Stripe account. */
    private Ledger ledger() {
        var biz = data.business(MerchantRole.OWNER);
        var fx = new SettingsFixtures(jdbc);
        var now = Instant.now();
        jdbc.sql(
                        "update merchants.merchants set stripe_account_id = 'acct_1Kx9PWM0000000Q2', take_rate_bps = 900 where id = ?")
                .params(biz.merchantId())
                .update();
        fx.verification(biz.merchantId(), "kyc", null, "passed", "verified", null, "kyc");
        var amvic = fx.verification(
                biz.merchantId(),
                "licence",
                "AMVIC",
                "44812",
                "verified",
                now.plus(Duration.ofDays(120)),
                "licence:AMVIC");
        fx.verification(
                biz.merchantId(), "licence", "AMVIC", "44812", "verified", now.plus(Duration.ofDays(120)), null);
        fx.verification(
                biz.merchantId(), "insurance", null, null, "verified", now.plus(Duration.ofDays(20)), "insurance");
        var wcb = fx.verification(
                biz.merchantId(), "wcb", "WCB Alberta", "clearance", "verified", now.minus(Duration.ofDays(8)), null);
        return new Ledger(biz.merchantId(), biz.userId(), wcb, amvic);
    }

    @Test
    void showsStripeTaxAndTheLedger_withEffectiveStates() throws Exception {
        var l = ledger();
        jdbc.sql("""
                        insert into payments.tax_jurisdiction_totals (merchant_id, period, jurisdiction, collected_cents, handling)
                        values (?, to_char(now() at time zone 'America/Edmonton', 'YYYY-"Q"Q'), 'ab_gst', 189240, 'remitted_by_northline')
                        """).params(l.merchantId()).update();

        mvc.perform(get("/api/v1/merchants/{id}/compliance", l.merchantId()).with(TestJwt.member(l.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripe.status").value("connected"))
                .andExpect(jsonPath("$.stripe.accountId").value("acct_1Kx9…Q2"))
                .andExpect(jsonPath("$.stripe.payoutsEnabled").value(true))
                .andExpect(jsonPath(
                        "$.stripe.requirements[*].kind",
                        contains("identity", "business", "bank", "owners", "annual_reverification")))
                .andExpect(jsonPath("$.stripe.requirements[4].state").value("due"))
                .andExpect(jsonPath("$.stripe.statementDescriptor").value("NORTHLINE* PRAIRIE WRENCH"))
                .andExpect(jsonPath("$.tax[0].jurisdiction").value("ab_gst"))
                .andExpect(jsonPath("$.tax[0].collectedCents").value(189240))
                .andExpect(jsonPath("$.business.takeRateBps").value(900))
                // KYC is shown under Stripe, and the duplicate AMVIC row is hidden.
                .andExpect(jsonPath("$.documents", hasSize(3)))
                .andExpect(jsonPath("$.documents[*].checkType", contains("licence", "insurance", "wcb")))
                .andExpect(jsonPath("$.documents[1].dueSoon").value(true))
                .andExpect(jsonPath("$.documents[2].status").value("expired"))
                .andExpect(jsonPath("$.documents[2].due").value(true))
                .andExpect(jsonPath("$.documents[2].pausesAt").isNotEmpty())
                .andExpect(jsonPath("$.dueCount").value(1))
                .andExpect(jsonPath("$.obligations.currentVersion").value("2.3"))
                .andExpect(jsonPath("$.obligations.upToDate").value(false));

        assertThat(complianceStatus.dueItems(l.merchantId())).singleElement().satisfies(d -> {
            assertThat(d.checkType()).isEqualTo("wcb");
            assertThat(d.status()).isEqualTo("expired");
            assertThat(d.pausesAt()).isEqualTo(d.expiresAt().plus(Duration.ofDays(15)));
        });
    }

    @Test
    void badgeCountsDueDocuments_kitchensShowAhs() throws Exception {
        var l = ledger();
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", l.merchantId()).with(TestJwt.member(l.owner())))
                .andExpect(jsonPath("$.compliance").value("1 due"));
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", l.merchantId())
                        .header("Accept-Language", "fr-CA")
                        .with(TestJwt.member(l.owner())))
                .andExpect(jsonPath("$.compliance").value("1 à faire"));

        var owner = data.user("Owner");
        var kitchen = data.merchant("kitchen", "Pho Dau Bo");
        data.member(kitchen, owner, MerchantRole.OWNER);
        new SettingsFixtures(jdbc)
                .verification(
                        kitchen,
                        "ahs_permit",
                        "AHS",
                        "FS-1",
                        "verified",
                        Instant.now().plus(Duration.ofDays(20)),
                        "ahs_permit");
        mvc.perform(get("/api/v1/merchants/{id}/nav-badges", kitchen).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.compliance").value("AHS"));
    }

    @Test
    void ownerUploadsARenewal_itWaitsForReview_andIsNoLongerDue() throws Exception {
        var l = ledger();
        mvc.perform(multipart("/api/v1/merchants/{id}/compliance/verifications/{v}/renewal", l.merchantId(), l.wcb())
                        .file(new MockMultipartFile("file", "wcb-2026.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .with(TestJwt.member(l.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.due").value(false));

        assertThat(events.stream(VerificationRenewalSubmitted.class)
                        .filter(e -> e.aggregateId().equals(l.wcb())))
                .singleElement()
                .satisfies(e -> assertThat(e.checkType()).isEqualTo("wcb"));
        assertThat(complianceStatus.dueItems(l.merchantId())).isEmpty();
        assertThat(jdbc.sql("""
                                select d.purpose from merchants.verifications v
                                  join merchants.documents d on d.id = v.document_media_id where v.id = ?
                                """).params(l.wcb()).query(String.class).single()).isEqualTo("verification");

        mvc.perform(multipart("/api/v1/merchants/{id}/compliance/verifications/{v}/renewal", l.merchantId(), l.wcb())
                        .file(new MockMultipartFile("file", "again.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .with(TestJwt.member(l.owner())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("renewal_pending"));
    }

    @Test
    void uploadMessages() throws Exception {
        var l = ledger();
        mvc.perform(multipart("/api/v1/merchants/{id}/compliance/verifications/{v}/renewal", l.merchantId(), l.amvic())
                        .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]))
                        .with(TestJwt.member(l.owner())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a file to upload."));
        mvc.perform(multipart("/api/v1/merchants/{id}/compliance/verifications/{v}/renewal", l.merchantId(), l.amvic())
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", new byte[] {1, 2}))
                        .with(TestJwt.member(l.owner())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Upload a PDF, PNG or JPEG under 10 MB."));
    }

    @Test
    void onlyTheOwnerUploads_andStrangersSeeNothing() throws Exception {
        var l = ledger();
        var bookkeeper = data.user("Priya");
        data.member(l.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(get("/api/v1/merchants/{id}/compliance", l.merchantId()).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/merchants/{id}/compliance/verifications/{v}/renewal", l.merchantId(), l.wcb())
                        .file(new MockMultipartFile("file", "wcb.pdf", "application/pdf", "%PDF".getBytes()))
                        .with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get("/api/v1/merchants/{id}/compliance", l.merchantId()).with(TestJwt.member(data.user("X"))))
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/compliance", l.merchantId()).with(TestJwt.memberWithoutMfa(l.owner())))
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void ownerAcceptsTheCurrentObligations() throws Exception {
        var l = ledger();
        mvc.perform(post("/api/v1/merchants/{id}/compliance/obligations", l.merchantId())
                        .with(TestJwt.member(l.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"2.2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("obligations_outdated"));
        mvc.perform(post("/api/v1/merchants/{id}/compliance/obligations", l.merchantId())
                        .with(TestJwt.member(l.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"2.3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedVersion").value("2.3"))
                .andExpect(jsonPath("$.upToDate").value(true));
    }

    @Test
    void stripeLinks_createTheExpressAccountWhenMissing() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{id}/compliance", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.stripe.status").value("not_connected"));
        mvc.perform(post("/api/v1/merchants/{id}/compliance/stripe-links", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"dashboard\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe_not_connected"));
        mvc.perform(post("/api/v1/merchants/{id}/compliance/stripe-links", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"update\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.url", startsWith("http://localhost:3100/b/" + biz.merchantId() + "/compliance")));
        mvc.perform(get("/api/v1/merchants/{id}/compliance", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.stripe.status").value("connected"));
    }
}
