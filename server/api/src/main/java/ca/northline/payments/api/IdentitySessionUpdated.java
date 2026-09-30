package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Stripe reported a change on an Identity VerificationSession ({@code identity.verification_session.*}, S-22) on the
 * platform webhook endpoint. Payments only receives, verifies and de-duplicates Stripe's deliveries (S-12); the
 * merchants module owns the owners' verifications and reacts to this. In-process only; no personal data — the
 * verified outputs are never in a webhook, and Northline reads them only to compare, in the merchants adapter.
 *
 * @param aggregateId the session id ({@code vs_…})
 * @param occurredAt Stripe's {@code created} of the event (the order updates are applied in)
 * @param status {@code requires_input | processing | verified | canceled}
 * @param lastErrorCode {@code last_error.code} ({@code document_expired}, {@code selfie_face_mismatch}, …)
 * @param merchantId {@code metadata.northline_merchant_id}, when Northline created the session
 */
public record IdentitySessionUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String status,
        @Nullable String lastErrorCode,
        @Nullable String merchantId)
        implements DomainEvent {}
