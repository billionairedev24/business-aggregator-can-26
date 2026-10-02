package ca.northline.region.api;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * A province or territory as the region configuration describes it (V130 rows, with REGION_PROVINCES overrides).
 *
 * @param code two-letter code
 * @param timeZones IANA zones, the first is the province's default
 * @param status launch status (live when configuration serves it, whatever the row says)
 * @param registries business-registry adapter keys serving the whole province (none = checked by hand)
 * @param holidays statutory holiday keys the province observes
 * @param taxBps combined sales-tax rate in basis points (GST 5 % = 500)
 * @param inFr / {@code ofFr}: the French name with its preposition / article ("au Québec", "du Québec")
 * @param language the province's language rules (S-116: French first, French listing text)
 */
public record ProvinceProfile(
        String code,
        String nameEn,
        String nameFr,
        List<ZoneId> timeZones,
        LaunchStatus status,
        PrivacyLaw privacyLaw,
        List<String> registries,
        List<String> holidays,
        int taxBps,
        String inFr,
        String ofFr,
        LanguageRules language) {

    public ProvinceProfile {
        timeZones = List.copyOf(timeZones);
        registries = List.copyOf(registries);
        holidays = List.copyOf(holidays);
        if (timeZones.isEmpty()) {
            throw new IllegalArgumentException("province " + code + " has no time zone");
        }
    }

    public ZoneId zone() {
        return timeZones.getFirst();
    }

    public String name(Locale locale) {
        return locale.getLanguage().equals("fr") ? nameFr : nameEn;
    }

    /** "in Alberta" / "au Québec": the name with the preposition the language needs. */
    public String nameIn(Locale locale) {
        return locale.getLanguage().equals("fr") ? inFr : "in " + nameEn;
    }

    /** "Alberta" / "du Québec": the name as a complement ("Consumer Protection Act {of}"). */
    public String nameOf(Locale locale) {
        return locale.getLanguage().equals("fr") ? ofFr : nameEn;
    }
}
