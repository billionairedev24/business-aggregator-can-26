package ca.northline.merchants.application;

import lombok.extern.slf4j.Slf4j;
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
}
