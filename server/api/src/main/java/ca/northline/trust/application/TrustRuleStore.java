package ca.northline.trust.application;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Outbound port (S-93): {@code trust.rules} and the review figures behind the rating floor. */
public interface TrustRuleStore {

    record Stored(String key, Map<String, Object> value, String updatedBy, Instant updatedAt) {}

    Map<String, Stored> all();

    Optional<Stored> find(String key);

    void save(String key, Map<String, Object> value, String staffId, String role, Instant at);

    /** {affected, total}: businesses with at least {@code minReviews} reviews since {@code since}, and those below. */
    long[] belowRating(double rating, Instant since, int minReviews, MerchantScope scope);

    /** S-82: each business with at least {@code minReviews} reviews since {@code since}: its average and count. */
    java.util.List<ca.northline.trust.api.TrustConsequences.Below> averages(Instant since, int minReviews);

    /** S-82: businesses with an open off-platform payment flag raised after one of theirs was warned since {@code since}. */
    java.util.List<String> warnedAgain(Instant since);
}
