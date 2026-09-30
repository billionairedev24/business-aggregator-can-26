package ca.northline.merchants.application;

import java.time.Instant;

/**
 * Internal event of the merchants module: an owner asked Northline to email another owner their Stripe Identity link;
 * {@link IdentityLinkDelivery} sends it after commit. Not externalized.
 *
 * <p>It carries Stripe's hosted-flow URL because the URL is never stored: it therefore sits in the Modulith event
 * registry ({@code events.event_publication[_archive]}), like the team invitation token (S-13). Accepted: the URL only
 * opens Stripe's flow for that one session (document + selfie of the person holding the ID), Stripe expires it, and a
 * new request replaces the session.
 */
public record IdentityLinkRequested(
        String eventId,
        Instant occurredAt,
        String merchantId,
        String checkId,
        String sessionId,
        String requesterId,
        String url) {}
