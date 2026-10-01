package ca.northline.region.api;

import java.time.ZoneId;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where a business is, as the region model sees it. Declared here so every module can ask (the merchants module
 * implements it from the onboarding province and city) (S-134, DECISIONS "Region-neutral by design"): its province and
 * city from onboarding, and the time zone its hours, cut-offs, holidays and reports are kept in — its market's, else
 * its province's, else the configured default province's, else the platform zone. Nothing here names a place.
 */
public interface MerchantPlaces {

    /** The business's place; an unknown business gets the defaults (never throws). */
    MerchantPlace of(String merchantId);

    /**
     * @param province the business's own province, else the configured default province; null when neither is set
     * @param ownProvince true when {@code province} is the business's own (onboarding recorded it)
     * @param city the business's city, null when not known
     * @param marketId the region market the city is, null when it is none
     */
    record MerchantPlace(
            @Nullable String province,
            boolean ownProvince,
            @Nullable String city,
            @Nullable String marketId,
            ZoneId zone,
            String provinceNameEn,
            String provinceNameFr,
            @Nullable ProvinceProfile profile) {

        /** The province's name in the locale ({@code ""} when no province is known). */
        public String provinceName(Locale locale) {
            return locale.getLanguage().equals("fr") ? provinceNameFr : provinceNameEn;
        }
    }
}
