package ca.northline.payments.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code payments.escrows}. A null {@code version} means new (INSERT). */
@Table(schema = "payments", name = "escrows")
record EscrowRow(
        @Id String id,
        @Nullable String paymentIntentId,
        String refType,
        String refId,
        String merchantId,
        long amountCents,
        @Nullable Instant releaseAt,
        @Nullable Instant releasedAt,
        String state,
        String kind,
        String label,
        @Nullable String orderNumber,
        @Nullable String customerId,
        @Nullable String customerName,
        @Nullable String listingId,
        @Nullable String listingName,
        @Nullable String source,
        int takeRateBps,
        long feeCents,
        long taxCents,
        Instant occurredAt,
        @Nullable Instant fulfilledAt,
        Instant createdAt,
        @Version @Nullable Integer version) {}
