package ca.northline.merchants.application;

import ca.northline.merchants.application.VerificationQueueStore.Review;
import ca.northline.shared.Conflict;
import ca.northline.shared.security.StaffAccessDenied;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Whether merchant applications need two people (2026-10-18): one reviews, another decides. Production can't turn it
 * off — the api refuses to start under {@code prod} with {@code northline.onboarding.four-eyes.enabled=false}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(FourEyesProperties.class)
class FourEyesPolicy {

    static final String PROD_REFUSED =
            "northline.onboarding.four-eyes.enabled=false is refused under the prod profile: production applications"
                    + " always need a reviewer and a different approver";

    private final boolean enabled;

    FourEyesPolicy(FourEyesProperties properties, Environment environment) {
        if (!properties.enabled() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(PROD_REFUSED);
        }
        if (!properties.enabled()) {
            log.warn("Two-person review is OFF (ONBOARDING_FOUR_EYES_ENABLED=false): one staff member can decide an"
                    + " application without a review");
        }
        this.enabled = properties.enabled();
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * Lets {@code approverId} decide an application, or refuses: 409 {@code review_required} while its open round has
     * no review, 403 {@code same_person} when the approver reviewed in that round. With the rule off, anyone may.
     *
     * @param reviews the application's reviews, newest first
     * @return the current review (the newest of the open round), null when there is none
     */
    @Nullable
    Review admit(List<Review> reviews, String approverId) {
        var round = reviews.stream().filter(Review::open).toList();
        var current = round.isEmpty() ? null : round.getFirst();
        if (!enabled) {
            return current;
        }
        if (current == null) {
            throw new Conflict("review_required", VerificationQueue.REVIEW_REQUIRED);
        }
        if (round.stream().anyMatch(r -> r.reviewedBy().equals(approverId))) {
            throw new StaffAccessDenied(StaffAccessDenied.Reason.SAME_PERSON, VerificationQueue.SAME_PERSON);
        }
        return current;
    }
}
