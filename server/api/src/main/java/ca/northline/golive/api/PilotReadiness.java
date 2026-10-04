package ca.northline.golive.api;

/**
 * S-118: the pilot businesses of a market as the go-live checklist needs them. Declared here and implemented by the
 * console, which derives each business's stage (S-120 {@code PilotOnboarding}); the checklist compares {@code ready} with
 * {@code GO_LIVE_MIN_PILOT_BUSINESSES}.
 */
public interface PilotReadiness {

    Counts counts(String marketId);

    /**
     * @param ready live, or waiting only for the market's launch (approved, page published, listings customers see —
     *     publicly visible by direct link)
     * @param total pilot businesses of the market
     * @param blocked with a blocker written down or found
     */
    record Counts(int ready, int total, int blocked) {}
}
