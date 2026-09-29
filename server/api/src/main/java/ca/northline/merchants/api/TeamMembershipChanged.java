package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.team_changed} — someone joined the team, changed role or was removed (Settings › Team &amp; roles).
 * The auth server's {@code merchants} token claim and cached permissions follow it. Topic {@code merchants.member},
 * key = merchant id (per-business ordering). Schema {@code events/merchants.team_membership_changed.v1.schema.json}.
 * Ids and role codes only.
 *
 * @param change {@code joined} | {@code role_changed} | {@code removed}
 * @param role the member's role after the change (null when removed)
 */
@Externalized("merchants.member::#{aggregateId()}")
public record TeamMembershipChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String userId,
        String change,
        @Nullable String role)
        implements DomainEvent {}
