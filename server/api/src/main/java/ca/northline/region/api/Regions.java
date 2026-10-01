package ca.northline.region.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The region model (S-134, DECISIONS "Region-neutral by design"): the single source of every place fact — provinces
 * (time zones, statutory holidays, tax, privacy law, registries, launch status) and their city markets (time zone,
 * centre, live flag). Rows in {@code region.regions} with configuration overrides ({@code northline.region.*}); code
 * never names a province, city or zone, it asks here with the business's or the visitor's place.
 */
public interface Regions {

    /** Every province and territory, served ones first (configuration order), then by sort. */
    List<ProvinceProfile> provinces();

    Optional<ProvinceProfile> province(@Nullable String code);

    /** Every market, by province then sort. */
    List<MarketProfile> markets();

    /** The market of a city (case-insensitive), within the province when one is given. */
    Optional<MarketProfile> market(@Nullable String city, @Nullable String province);

    Optional<MarketProfile> marketById(@Nullable String id);

    /** The time zone of a place: its market's, else its province's, else the platform zone. */
    ZoneId zone(@Nullable String province, @Nullable String city);

    /** The zone of platform-wide work that belongs to no market (nightly jobs, support hours, account dates). */
    ZoneId platformZone();

    /** The province's statutory holidays in a year, by date; none for an unknown province. */
    List<Holiday> holidays(@Nullable String province, int year);

    /** The province's name in the locale; the code itself when unknown. */
    default String provinceName(@Nullable String code, Locale locale) {
        return province(code).map(p -> p.name(locale)).orElse(code == null ? "" : code);
    }

    /** The next {@code count} holidays of the province on or after {@code from}. */
    default List<Holiday> upcomingHolidays(@Nullable String province, LocalDate from, int count) {
        return Stream.concat(
                        holidays(province, from.getYear()).stream(), holidays(province, from.getYear() + 1).stream())
                .filter(h -> !h.date().isBefore(from))
                .sorted(Comparator.comparing(Holiday::date))
                .limit(count)
                .toList();
    }

    /** The province's holiday on that date, if it is one. */
    default Optional<Holiday> holiday(@Nullable String province, LocalDate date) {
        return holidays(province, date.getYear()).stream()
                .filter(h -> h.date().equals(date))
                .findFirst();
    }

    /** Registry adapter keys for a business: its province's, then its city market's. Nothing is assumed. */
    default List<String> registries(@Nullable String province, @Nullable String city) {
        var out = new java.util.ArrayList<String>();
        province(province).ifPresent(p -> out.addAll(p.registries()));
        market(city, province).ifPresent(m -> out.addAll(m.registries()));
        return List.copyOf(out);
    }

    /** Re-reads the region rows now (after the console edits them); otherwise they are re-read every cache period. */
    void refresh();
}
