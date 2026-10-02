package ca.northline.region.application;

import ca.northline.region.api.PrivacyLaw;
import java.util.Optional;

/** Outbound port: the privacy laws' rows ({@code region.privacy_laws}, V271). */
public interface PrivacyLawStore {

    Optional<LawRow> law(PrivacyLaw law);

    record LawRow(
            String nameEn,
            String nameFr,
            String shortEn,
            String shortFr,
            String authorityEn,
            String authorityFr,
            String authorityUrl,
            int responseDays,
            boolean businessDays,
            int extensionDays) {}
}
