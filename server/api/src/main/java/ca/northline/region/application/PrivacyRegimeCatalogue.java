package ca.northline.region.application;

import ca.northline.region.api.Holiday;
import ca.northline.region.api.Markets;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.api.PrivacyRegime;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.region.api.ProvinceProfile;
import ca.northline.region.api.Regions;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** {@link PrivacyRegimes} over the region model: the province's law, then the law's row. */
@Service
@RequiredArgsConstructor
class PrivacyRegimeCatalogue implements PrivacyRegimes {

    private static final LocalTime LAST_SECOND = LocalTime.of(23, 59, 59);

    private final Regions regions;
    private final Markets markets;
    private final PrivacyLawStore laws;

    @Override
    public PrivacyRegime forProvince(@Nullable String province) {
        var profile = regions.province(province).or(() -> regions.province(markets.defaultProvince()));
        return of(
                profile.map(ProvinceProfile::privacyLaw).orElse(PrivacyLaw.PIPEDA),
                profile.map(ProvinceProfile::code).orElse(province == null ? "" : province));
    }

    @Override
    public PrivacyRegime of(PrivacyLaw law, String province) {
        var own = laws.law(law);
        var row = own.or(() -> laws.law(PrivacyLaw.PIPEDA))
                .orElseThrow(() -> new IllegalStateException("region.privacy_laws has no row for " + law.code()));
        return new PrivacyRegime(
                own.isPresent() ? law : PrivacyLaw.PIPEDA,
                province,
                row.nameEn(),
                row.nameFr(),
                row.shortEn(),
                row.shortFr(),
                row.authorityEn(),
                row.authorityFr(),
                row.authorityUrl(),
                row.responseDays(),
                row.businessDays(),
                row.extensionDays());
    }

    @Override
    public Instant deadline(PrivacyRegime regime, Instant from, int days) {
        var zone = regions.zone(regime.province().isEmpty() ? null : regime.province(), null);
        var day = from.atZone(zone).toLocalDate();
        if (!regime.businessDays()) {
            return endOf(day.plusDays(days), zone);
        }
        var holidays = new HashSet<LocalDate>();
        var counted = 0;
        while (counted < days) {
            day = day.plusDays(1);
            if (holidays.isEmpty() || day.getDayOfYear() == 1) {
                holidays.addAll(holidaysOf(regime.province(), day.getYear()));
            }
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY
                    && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(day)) {
                counted++;
            }
        }
        return endOf(day, zone);
    }

    private Set<LocalDate> holidaysOf(String province, int year) {
        var dates = new HashSet<LocalDate>();
        regions.holidays(province.isEmpty() ? null : province, year).stream()
                .map(Holiday::date)
                .forEach(dates::add);
        return dates;
    }

    private static Instant endOf(LocalDate day, ZoneId zone) {
        // whole seconds: the database keeps microseconds and would round LocalTime.MAX up to the next day
        return day.atTime(LAST_SECOND).atZone(zone).toInstant();
    }
}
