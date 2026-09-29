package ca.northline.trust.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The latest nightly quality score of a business ({@code trust.quality_scores}) — the dashboard's "Quality score · 91"
 * panel: on-time arrival, completion photos, response &lt; 2 h, re-book rate and the (inverted) dispute rate, each
 * against its floor.
 */
public interface QualityQuery {

    /**
     * One score component.
     *
     * @param key {@code on_time | photos | response | rebook | disputes}
     * @param value the measured percentage (98 = 98 %; for {@code disputes} 0.3 = 0.3 %)
     * @param floor the floor for the business's tier, in the same unit
     * @param bar bar length 0–100 ({@code disputes} is inverted: 100 − 10 × rate, so 0.3 % → 97)
     * @param barFloor the floor on the same 0–100 scale
     * @param inverted true when lower values are better ({@code disputes})
     */
    record Component(String key, double value, double floor, int bar, int barFloor, boolean inverted) {}

    record QualityScore(String merchantId, LocalDate date, int score, List<Component> components) {}

    Optional<QualityScore> latest(String merchantId);
}
