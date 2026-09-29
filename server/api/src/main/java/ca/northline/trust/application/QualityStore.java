package ca.northline.trust.application;

import ca.northline.trust.api.QualityQuery.QualityScore;
import java.util.Optional;

/** Outbound port: nightly quality scores ({@code trust.quality_scores}). */
public interface QualityStore {

    Optional<QualityScore> latest(String merchantId);
}
