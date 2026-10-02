package ca.northline.region;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S-105: which privacy law applies and its deadlines are region data (V130's province rows, V271's laws) — no province
 * or law in code. BC counts business days (its holidays from the region model).
 */
class PrivacyRegimesTest extends IntegrationTest {

    static final Instant FRIDAY = Instant.parse("2026-10-02T18:00:00Z");

    @Autowired
    PrivacyRegimes regimes;

    @Test
    void eachProvinceGetsItsLawWithItsDeadlines() {
        var ab = regimes.forProvince("AB");
        assertThat(ab.law()).isEqualTo(PrivacyLaw.AB_PIPA);
        assertThat(ab.responseDays()).isEqualTo(45);
        assertThat(ab.extensionDays()).isEqualTo(30);
        assertThat(ab.authority(Locale.CANADA_FRENCH)).startsWith("Commissariat");

        var qc = regimes.forProvince("QC");
        assertThat(qc.law()).isEqualTo(PrivacyLaw.QC_LAW25);
        assertThat(qc.extendable()).isFalse();
        assertThat(qc.shortName(Locale.CANADA_FRENCH)).isEqualTo("Loi 25");

        var sk = regimes.forProvince("SK");
        assertThat(sk.law()).isEqualTo(PrivacyLaw.PIPEDA);
        assertThat(sk.province()).isEqualTo("SK");
        assertThat(sk.shortName(Locale.CANADA_FRENCH)).isEqualTo("LPRPDE");
        assertThat(regimes.deadline(sk, FRIDAY, 30)
                        .atZone(ZoneId.of("America/Regina"))
                        .toLocalDate())
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void unknownProvincesGetTheDefaultProvincesLaw() {
        assertThat(regimes.forProvince("ZZ").law()).isEqualTo(PrivacyLaw.AB_PIPA);
        assertThat(regimes.forProvince(null).province()).isEqualTo("AB");
    }

    @Test
    void businessDaysSkipWeekendsAndTheProvincesHolidays() {
        var bc = regimes.forProvince("BC");
        assertThat(bc.businessDays()).isTrue();
        // from Friday 2 October: Thanksgiving (12 Oct) and Remembrance Day (11 Nov) don't count
        assertThat(regimes.deadline(bc, FRIDAY, 30)
                        .atZone(ZoneId.of("America/Vancouver"))
                        .toLocalDate())
                .isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(regimes.deadline(bc, FRIDAY, 1)
                        .atZone(ZoneId.of("America/Vancouver"))
                        .toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 5));
    }
}
