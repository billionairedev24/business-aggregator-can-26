package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code storefront.published} — the business page / store / menu page was published: CDN purge, search re-index of
 * the merchant card. Topic {@code merchants.storefront}, key = storefront id. Schema:
 * {@code resources/events/merchants.storefront_published.v1.schema.json}.
 *
 * @param sections enabled section kinds in page order
 * @param customDomain verified custom domain, if any
 */
@Externalized("merchants.storefront::#{aggregateId()}")
public record StorefrontPublished(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String merchantId,
        String slug,
        String pageKind,
        List<String> sections,
        @Nullable String customDomain)
        implements DomainEvent {
    public StorefrontPublished {
        sections = List.copyOf(sections);
    }
}
