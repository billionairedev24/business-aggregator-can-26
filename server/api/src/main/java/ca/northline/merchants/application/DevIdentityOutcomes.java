package ca.northline.merchants.application;

import java.util.List;
import java.util.Optional;

/**
 * LOCAL ONLY: the fake Stripe Identity's page lets the developer pick how a session ends. Implemented by the local
 * adapter; absent with {@code northline.identity.provider=stripe}.
 */
public interface DevIdentityOutcomes {

    /** Outcome keys offered on the page, in order ({@code verified}, {@code name_mismatch}, …). */
    List<String> outcomes();

    /**
     * Records how a session the fake created will read, without applying it (tests then deliver a signed webhook).
     *
     * @return the session's return URL; empty for an unknown session or outcome
     */
    Optional<String> prepare(String sessionId, String outcome);

    /**
     * Records the outcome for a session the fake created and applies it as Stripe's webhook would.
     *
     * @return where to send the browser next (the session's return URL); empty for an unknown session
     */
    Optional<String> finish(String sessionId, String outcome);
}
