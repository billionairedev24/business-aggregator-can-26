package ca.northline.console.application;

/**
 * The automatic consequences of the trust rules (S-93's configuration, S-82 enforces them), run nightly: a business
 * below the rating floor is hidden from search until its average recovers; a business that mentions off-platform
 * payment again after a warning is suspended. Each change goes through {@code merchants.api.SellerSanctions} (trail,
 * audit, event, owners' email with the reason).
 */
public interface EnforceTrustRules {

    /** How long a warning counts: an off-platform mention within this after it suspends. */
    java.time.Duration WARNING_WINDOW = java.time.Duration.ofDays(180);

    Result run();

    record Result(int hidden, int restored, int suspended) {}
}
