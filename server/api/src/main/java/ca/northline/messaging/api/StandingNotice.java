package ca.northline.messaging.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-156: something changed in a business's standing that its owners must hear about — Northline paused or resumed
 * instant book, an appeal was decided, a coaching plan started. Published by the standing module (which depends on
 * messaging, so the notice type lives here); the messaging module emails the owners. In-process only (not
 * externalized): it carries the reason written for the business.
 *
 * @param aggregateId the consequence, appeal or coaching plan id
 * @param change {@code instant_book_paused} | {@code instant_book_resumed} | {@code appeal_granted} |
 *     {@code appeal_denied} | {@code coaching_started}
 * @param subject what an appeal was about: {@code dispute} or a consequence kind ({@code suspended},
 *     {@code search_hidden}, {@code instant_book_off}); null otherwise
 * @param reference the appeal's number ({@code AP-1001}) or the dispute's case number; null otherwise
 * @param compensationCents what Northline paid the business for a granted dispute appeal
 */
public record StandingNotice(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String change,
        @Nullable String subject,
        String reason,
        @Nullable String reference,
        long compensationCents)
        implements DomainEvent {}
