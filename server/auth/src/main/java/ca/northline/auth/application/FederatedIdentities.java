package ca.northline.auth.application;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: Google / Apple accounts linked to Northline accounts ({@code auth.federated_identities}, S-18). */
public interface FederatedIdentities {

    /** The Northline account this provider account is linked to. */
    Optional<String> userOf(String provider, String subject);

    /** Links (idempotent: an existing link of this provider account is kept as it is). */
    void link(String provider, String subject, String userId, @Nullable String email, boolean privateRelay, Instant at);

    /** Signed in with it again: "last used", and the email the provider reports now. */
    void used(String provider, String subject, @Nullable String email, boolean privateRelay, Instant at);
}
