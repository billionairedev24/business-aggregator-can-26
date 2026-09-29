package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** The onboarding wizard's steps in order (design 02 {@code obKeys}); {@code DONE} once the owner went live. */
public enum OnboardingStep implements CodedEnum {
    ACCOUNT,
    BUSINESS,
    VERIFICATION,
    REVIEW,
    PAGE,
    LISTINGS,
    DONE;

    /** Review, Page, Listings and Done are only reachable once the application was submitted. */
    public boolean needsSubmission() {
        return compareTo(REVIEW) >= 0;
    }

    public OnboardingStep furthest(OnboardingStep other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
