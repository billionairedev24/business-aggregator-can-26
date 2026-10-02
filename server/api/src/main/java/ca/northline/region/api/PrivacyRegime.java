package ca.northline.region.api;

import java.util.Locale;

/**
 * A privacy law as a request is handled under it ({@code region.privacy_laws}, S-105): its names, the regulator a
 * person may complain to, and its deadlines. Which law applies is the person's province ({@link
 * ProvinceProfile#privacyLaw()}); nothing in code names a law's deadline or a province.
 *
 * @param province the province the regime was chosen for (two-letter code)
 * @param responseDays days to answer a request (business days when {@code businessDays})
 * @param extensionDays the one extension the law allows (0 = none)
 * @param decisionRetentionDays S-107: information used to make a decision about a person (a dispute decided with it)
 *     is kept at least this many days after the decision (0 = the law sets no number)
 */
public record PrivacyRegime(
        PrivacyLaw law,
        String province,
        String nameEn,
        String nameFr,
        String shortEn,
        String shortFr,
        String authorityEn,
        String authorityFr,
        String authorityUrl,
        int responseDays,
        boolean businessDays,
        int extensionDays,
        int decisionRetentionDays) {

    public String name(Locale locale) {
        return locale.getLanguage().equals("fr") ? nameFr : nameEn;
    }

    public String shortName(Locale locale) {
        return locale.getLanguage().equals("fr") ? shortFr : shortEn;
    }

    public String authority(Locale locale) {
        return locale.getLanguage().equals("fr") ? authorityFr : authorityEn;
    }

    public boolean extendable() {
        return extensionDays > 0;
    }
}
