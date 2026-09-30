package ca.northline.merchants.application;

import java.time.Instant;

/**
 * Internal event of the merchants module: an owner created an invitation; {@link TeamInvitationDelivery} sends it after
 * the transaction committed. Not externalized (never on Kafka).
 *
 * <p>It carries the link's token because only its hash is stored and the email needs the link. The token therefore
 * sits in the Modulith event registry ({@code events.event_publication[_archive]}) — acceptable because a token alone
 * can't join a team (accepting needs a signed-in account whose email or mobile matches the invitation, plus MFA) and it
 * expires after 7 days (docs/DECISIONS.md § S-13).
 */
public record TeamInvitationIssued(
        String eventId, Instant occurredAt, String invitationId, String merchantId, String inviterId, String token) {}
