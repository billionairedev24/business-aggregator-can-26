package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.api.MerchantVerifications;
import ca.northline.payments.api.MerchantBillingFacts;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.tools.CategorySeeder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-37: the merchants module's public queries that replaced other modules' SQL on {@code merchants.*} (payments' tier,
 * take rate and province; messaging's type and tier; food's approval and food-safety evidence; catalogue's licence
 * check), and the catalogue's {@link CategorySource} that replaced merchants' SQL on {@code catalogue.categories}.
 */
class MerchantQueryApiTest extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-30T18:00:00Z");

    @Autowired
    MerchantDirectory directory;

    @Autowired
    MerchantVerifications verifications;

    @Autowired
    MerchantBillingFacts billing;

    @Autowired
    CategorySource categories;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void profileCarriesTypeTierStatusTakeRateAndProvince() {
        var id = data.merchant("seller", "Directory Parts");
        jdbc.sql("update merchants.merchants set tier = 'master', take_rate_bps = 800, province = 'AB' where id = ?")
                .params(id)
                .update();

        assertThat(directory.profile(id)).hasValueSatisfying(p -> {
            assertThat(p.type()).isEqualTo("seller");
            assertThat(p.tier()).isEqualTo("master");
            assertThat(p.active()).isTrue();
            assertThat(p.takeRateBps()).isEqualTo(800);
            assertThat(p.province()).isEqualTo("AB");
        });
        assertThat(billing.billing(id)).contains(new MerchantBillingFacts.Billing("master", 800, "AB"));
        assertThat(directory.profile(Ids.next())).isEmpty();
        assertThat(billing.billing(Ids.next())).isEmpty();
    }

    @Test
    void pausedBusinessIsNotActive() {
        var id = data.merchant("kitchen", "Paused Kitchen");
        jdbc.sql("update merchants.merchants set status = 'paused' where id = ?")
                .params(id)
                .update();
        assertThat(directory.profile(id))
                .hasValueSatisfying(p -> assertThat(p.active()).isFalse());
    }

    @Test
    void licenceMustBeVerifiedUnexpiredAndForTheRegistry() {
        var id = data.merchant("provider", "Licensed Mechanic");
        verification(id, "licence", "AMVIC", "verified", NOW.plus(30, ChronoUnit.DAYS), "L-1");
        verification(id, "licence", "AGLC", "verified", NOW.minus(1, ChronoUnit.DAYS), "L-2");
        verification(id, "registry", "RECA", "submitted", null, "R-1");

        assertThat(verifications.hasVerifiedLicence(id, "amvic", NOW)).isTrue();
        assertThat(verifications.hasVerifiedLicence(id, "AMVIC", NOW.plus(31, ChronoUnit.DAYS)))
                .isFalse();
        assertThat(verifications.hasVerifiedLicence(id, "AGLC", NOW))
                .as("expired")
                .isFalse();
        assertThat(verifications.hasVerifiedLicence(id, "RECA", NOW))
                .as("not verified")
                .isFalse();
        assertThat(verifications.hasVerifiedLicence(data.merchant("provider", "Other"), "AMVIC", NOW))
                .isFalse();
    }

    @Test
    void latestEvidencePrefersAVerifiedRow() {
        var id = data.merchant("kitchen", "Evidence Kitchen");
        verification(id, "ahs_permit", null, "verified", NOW.plus(90, ChronoUnit.DAYS), "AHS-100");
        verification(id, "ahs_permit", null, "submitted", null, "AHS-101");

        assertThat(verifications.latest(id, "ahs_permit")).hasValueSatisfying(e -> {
            assertThat(e.reference()).isEqualTo("AHS-100");
            assertThat(e.status()).isEqualTo("verified");
            assertThat(e.expiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
        });
        assertThat(verifications.latest(id, "food_cert")).isEmpty();
    }

    @Test
    void categorySourceReadsTheCatalogueTaxonomy() {
        new CategorySeeder(dataSource).seed();
        var mechanic = "service.automotive.mobile-mechanic";

        assertThat(categories.byIds(List.of(mechanic, "no.such.category")))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.parentId()).isEqualTo("service.automotive");
                    assertThat(c.root()).isEqualTo("service");
                    assertThat(c.names()).containsEntry("en", "Mobile mechanic");
                    assertThat(c.regulatedRegistry()).isEqualTo("AMVIC");
                });
        assertThat(categories.byRoots(List.of("service")))
                .extracting(CategorySource.Category::id)
                .contains("service.automotive", mechanic)
                .noneMatch(c -> c.startsWith("shop."));
        assertThat(categories.byIds(List.of())).isEmpty();
    }

    private void verification(
            String merchantId,
            String checkType,
            @org.jspecify.annotations.Nullable String registry,
            String status,
            @org.jspecify.annotations.Nullable Instant expiresAt,
            String reference) {
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_type, registry, status, expires_at,
                          reference, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        Ids.next(),
                        merchantId,
                        checkType,
                        registry,
                        status,
                        expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                        reference)
                .update();
    }
}
