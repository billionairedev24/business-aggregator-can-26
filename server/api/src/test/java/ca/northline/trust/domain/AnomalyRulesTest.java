package ca.northline.trust.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.trust.domain.AnomalyRules.Signal;
import ca.northline.trust.domain.AnomalyRules.Week;
import org.junit.jupiter.api.Test;

class AnomalyRulesTest {

    @Test
    void aBurstIsThreeTimesTheUsualWeekAndAtLeastFive() {
        assertThat(AnomalyRules.signals(new Week(6, 5.0, 16, 4.5, 0, 0))).containsExactly(Signal.REVIEW_BURST);
        assertThat(AnomalyRules.signals(new Week(5, 5.0, 16, 4.5, 0, 0))).isEmpty(); // 2 a week usually: 5 < 6
        assertThat(AnomalyRules.signals(new Week(4, 5.0, 0, null, 0, 0))).isEmpty();
        assertThat(AnomalyRules.signals(new Week(5, 5.0, 0, null, 0, 0))).containsExactly(Signal.REVIEW_BURST);
    }

    @Test
    void aRatingDropNeedsEnoughReviewsOnBothSides() {
        assertThat(AnomalyRules.signals(new Week(3, 3.0, 5, 4.0, 0, 0))).containsExactly(Signal.RATING_DROP);
        assertThat(AnomalyRules.signals(new Week(2, 1.0, 50, 4.8, 0, 0))).isEmpty();
        assertThat(AnomalyRules.signals(new Week(3, 3.1, 50, 4.0, 0, 0))).isEmpty();
        assertThat(AnomalyRules.signals(new Week(3, 1.0, 4, 5.0, 0, 0))).isEmpty();
    }

    @Test
    void flagsCountFromThree() {
        assertThat(AnomalyRules.signals(new Week(0, null, 0, null, 3, 2))).containsExactly(Signal.OFF_PLATFORM);
        assertThat(AnomalyRules.signals(new Week(0, null, 0, null, 2, 3))).containsExactly(Signal.AI_FLAGS);
    }

    @Test
    void theRulesExplainThemselvesWithTheNumbers() {
        var week = new Week(12, 5.0, 16, 4.4, 0, 0);
        assertThat(AnomalyRules.describe(AnomalyRules.signals(week), week))
                .isEqualTo("Stands out this week: review burst — reviews this week 12 (previous 8 weeks: 16, about 2.0"
                        + " a week); average rating this week 5.0 (before 4.4); off-platform payment flags 0; screening"
                        + " flags 0.");
    }
}
