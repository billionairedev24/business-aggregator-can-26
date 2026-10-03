package ca.northline.trust.application;

import ca.northline.trust.api.QualityQuery.QualityScore;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Outbound port: nightly quality scores ({@code trust.quality_scores}). */
public interface QualityStore {

    Optional<QualityScore> latest(String merchantId);

    /** S-119: the latest score of each of many businesses in one query; one without a score is absent. */
    Map<String, QualityScore> latest(Collection<String> merchantIds);
}
