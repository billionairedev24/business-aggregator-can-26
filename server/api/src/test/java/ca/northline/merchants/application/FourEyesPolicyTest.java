package ca.northline.merchants.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.merchants.application.VerificationQueue.Recommendation;
import ca.northline.merchants.application.VerificationQueueStore.Review;
import ca.northline.shared.Conflict;
import ca.northline.shared.security.StaffAccessDenied;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Two-person review (2026-10-18): who may decide an application, with the rule on and off. */
class FourEyesPolicyTest {

    static final Instant T = Instant.parse("2026-10-18T15:00:00Z");

    static Review review(String id, String by, @Nullable String decisionId) {
        return new Review(id, "m1", Recommendation.APPROVE, List.of(), null, by, T, decisionId);
    }

    static FourEyesPolicy policy(boolean enabled, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new FourEyesPolicy(new FourEyesProperties(enabled), environment);
    }

    @Test
    void anotherPersonDecides_theNewestReviewOfTheOpenRound() {
        var current = policy(true).admit(List.of(review("r2", "ana", null), review("r1", "ana", "d0")), "ben");
        assertThat(current).extracting(Review::id).isEqualTo("r2");
    }

    @Test
    void theReviewerCantDecide_norAnyoneWhoReviewedInTheRound() {
        var round = List.of(review("r3", "cy", null), review("r2", "ana", null));
        assertThatThrownBy(() -> policy(true).admit(round, "ana"))
                .isInstanceOfSatisfying(StaffAccessDenied.class, e -> {
                    assertThat(e.reason()).isEqualTo(StaffAccessDenied.Reason.SAME_PERSON);
                    assertThat(e).hasMessage(VerificationQueue.SAME_PERSON);
                });
        assertThat(policy(true).admit(round, "ben")).extracting(Review::id).isEqualTo("r3");
    }

    @Test
    void aDecisionNeedsAReview_closedRoundsDontCount() {
        // ana reviewed the last round, which a decision closed: she may decide this one once someone reviews it
        var closed = List.of(review("r1", "ana", "d1"));
        assertThatThrownBy(() -> policy(true).admit(closed, "ben"))
                .isInstanceOfSatisfying(
                        Conflict.class, e -> assertThat(e.getCode()).isEqualTo("review_required"));
        assertThat(policy(true).admit(List.of(review("r2", "ben", null), review("r1", "ana", "d1")), "ana"))
                .extracting(Review::id)
                .isEqualTo("r2");
    }

    @Test
    void off_onePersonMayDecide_withOrWithoutAReview() {
        assertThat(policy(false).enabled()).isFalse();
        assertThat(policy(false).admit(List.of(), "ana")).isNull();
        assertThat(policy(false).admit(List.of(review("r1", "ana", null)), "ana"))
                .extracting(Review::id)
                .isEqualTo("r1");
    }

    @Test
    void productionRefusesToTurnItOff() {
        assertThatThrownBy(() -> policy(false, "prod", "cloud"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FourEyesPolicy.PROD_REFUSED);
        assertThat(policy(true, "prod").enabled()).isTrue();
        assertThat(policy(false, "dev").enabled()).isFalse();
    }
}
