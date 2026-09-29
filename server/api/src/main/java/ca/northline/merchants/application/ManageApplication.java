package ca.northline.merchants.application;

import ca.northline.merchants.domain.OnboardingStep;

/** Small onboarding use cases, one interface each. */
public final class ManageApplication {
    private ManageApplication() {}

    /** The wizard's state for resuming and rendering every step. */
    public interface ViewOnboarding {
        OnboardingView view(String merchantId);
    }

    /** Remembers how far the owner got; steps after Verification need a submitted application. */
    public interface AdvanceOnboarding {
        OnboardingView advance(String merchantId, OnboardingStep step);
    }

    /** "Submit for review": applicant → pending, publishes {@code merchant.submitted}. */
    public interface SubmitApplication {
        OnboardingView submit(String merchantId, String actorId);
    }

    /** Trust &amp; safety approval: pending → active, publishes {@code merchant.approved} (dev endpoint only for now). */
    public interface ApproveApplication {
        OnboardingView approve(String merchantId, String actorId);
    }
}
