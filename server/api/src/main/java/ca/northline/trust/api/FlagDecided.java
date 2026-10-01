package ca.northline.trust.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Staff decided a trust &amp; safety flag in the console (S-133 queue, S-92 vetting, S-93 trust): dismissed or
 * actioned. In-process only. The module that owns the flagged thing decides what an actioned flag does to it — for a
 * listing ({@code targetType = listing}) the catalogue rejects it (S-92, DECISIONS "S-133 open question").
 *
 * @param aggregateId the flag id
 * @param targetType {@code listing | review | message | merchant | …}
 * @param decision {@code dismissed | actioned}
 * @param role the console role(s) the staff member acted with
 * @param note the staff member's note, if any
 * @param action what staff did (S-93: {@code warn | coach | confirm | suspend_listings | escalate}), or the decision
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
        @Nullable String note,
        String action)
        implements DomainEvent {}
