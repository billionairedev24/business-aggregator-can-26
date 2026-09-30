package ca.northline.catalogue.application;

import org.jspecify.annotations.Nullable;

/**
 * Internal events of the S-35 sync (not in {@code api}, not externalized). Published in the transaction that caused
 * them, so the Modulith registry (outbox) runs the work after commit, and again after a crash.
 */
public final class CommerceSyncEvents {
    private CommerceSyncEvents() {}

    /** A merchant connected: register webhooks and import the catalogue. */
    public record CommerceConnected(String integrationId) {}

    /** A verified webhook named a product (null = the platform didn't say which: read everything). */
    public record CommerceProductChanged(
            String integrationId, @Nullable String externalId) {}

    /** A verified webhook said a product was deleted. */
    public record CommerceProductRemoved(String integrationId, String externalId) {}
}
