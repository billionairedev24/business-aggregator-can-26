package ca.northline.merchants.application;

import java.time.Instant;

/**
 * Internal event of the merchants module (S-120): staff created a pilot invite; {@link PilotInviteDelivery} emails it
 * after the transaction committed. Not externalized. It carries the link's token because only its hash is stored —
 * the same trade-off as {@link TeamInvitationIssued} (a token alone opens nothing but the Account step of a signed-in
 * person, and it expires).
 *
 * @param language {@code en} or {@code fr}
 */
public record PilotInviteIssued(
        String eventId,
        Instant occurredAt,
        String inviteId,
        String pilotId,
        String inviterId,
        String token,
        String language) {}
