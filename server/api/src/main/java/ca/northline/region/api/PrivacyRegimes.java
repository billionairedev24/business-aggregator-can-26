package ca.northline.region.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The privacy law a person is under and its deadlines (S-105), from the region model: the province's {@code
 * privacy_law} (V130) and the law's row in {@code region.privacy_laws} (V271).
 */
public interface PrivacyRegimes {

    /**
     * The regime of a province; for an unknown or missing province, the configured default province's (and PIPEDA when
     * there is none: it applies everywhere).
     */
    PrivacyRegime forProvince(@Nullable String province);

    /** A law's regime as applied in a province (a request keeps the law it was received under). */
    PrivacyRegime of(PrivacyLaw law, String province);

    /**
     * The end of the {@code days}-th day after {@code from}, in the province's time zone; with {@code businessDays}
     * the count skips Saturdays, Sundays and the province's holidays.
     */
    Instant deadline(PrivacyRegime regime, Instant from, int days);
}
