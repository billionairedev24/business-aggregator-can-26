package ca.northline.trust.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Staff decided a trust &amp; safety flag in the console (S-133 queue, S-92 vetting, S-93 trust): {@code dismissed} or
 * {@code actioned}. In-process only. The module that owns the flagged thing decides what "actioned" does to it — for a
 * listing ({@code targetType = listing}) the catalogue rejects it (S-92, DECISIONS "S-133 open question").
 *
 * @param aggregateId the flag id
 * @param targetType {@code listing | review | message | merchant | …}
 * @param decision {@code dismissed | actioned}
 * @param role the console role(s) the staff member acted with
 * @param note the staff member's note, if any
 */
public record FlagDecided(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String targetType,
        String targetId,
        String rule,
        @Nullable String merchantId,
        String decision,
        String actorId,
        String role,
        @Nullable String note)
        implements DomainEvent {}
