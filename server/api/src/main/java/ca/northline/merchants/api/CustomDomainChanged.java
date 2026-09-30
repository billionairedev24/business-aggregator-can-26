package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code custom_domain.changed} — a storefront's custom domain was connected, disconnected or changed state (S-31):
 * pending → verified → issuing → live, failed, expired. The consumer app evicts its host → storefront cache on it; the
 * api emails the owners when {@code notice} is set. Topic {@code merchants.storefront}, key = storefront id. Schema:
 * {@code resources/events/merchants.custom_domain_changed.v1.schema.json}. A business's domain is public, not personal
 * data.
 *
 * @param domain the domain (lower-case ASCII / punycode)
 * @param status {@code pending | verified | issuing | live | failed | expired}; null = no longer connected to this page
 * @param previousStatus the status before, null for a newly connected domain
 * @param problem what keeps it from the next state ({@code txt_missing}, {@code not_pointing}, {@code certificate} …)
 * @param notice why the owners are told: {@code live | dns_lost | unverified | certificate_failed | expired | released}
 * @param graceEndsAt while the records don't point at us: when the domain stops being served
 */
@Externalized("merchants.storefront::#{aggregateId()}")
public record CustomDomainChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String domain,
        @Nullable String status,
        @Nullable String previousStatus,
        @Nullable String problem,
        @Nullable String notice,
        @Nullable Instant graceEndsAt)
        implements DomainEvent {}
