package ca.northline.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S-156: S-93's "customer no-show ×2 → reliability score drop; providers see it before accepting", on the customer's
 * account. The standing module restricts a customer when they reach the rule's count of confirmed no-shows: providers
 * see the count on quote requests and jobs ({@link PersonDirectory.Person#noShows()}), and the customer can't book
 * instantly — a provider accepts a request first. Staff lift it.
 */
public interface CustomerNoShows {

    String INSTANT_REFUSED =
            "After missed appointments, providers confirm your bookings first — ask for a quote instead.";

    /** Restricts (or updates the count of) a customer; idempotent. */
    void restrict(String userId, int confirmedNoShows, Instant at);

    /** False when the customer wasn't restricted. */
    boolean lift(String userId);

    Optional<Restriction> of(String userId);

    /** Every restricted customer's id. */
    List<String> restricted();

    record Restriction(int noShows, Instant since) {}
}
