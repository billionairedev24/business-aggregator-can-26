package ca.northline.merchants.application;

import java.util.Locale;

/** Outbound port: emails an owner the Stripe Identity link (S-22), through the S-13 email library. */
public interface IdentityLinkSender {

    /**
     * Sends once per {@code deliveryId}.
     *
     * @throws RuntimeException when the provider can't take it now (the listener is retried later)
     */
    void send(String deliveryId, Link link);

    /** @param locale the requesting owner's language */
    record Link(String email, String ownerName, String businessName, String requesterName, String url, Locale locale) {}
}
