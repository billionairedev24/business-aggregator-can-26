package ca.northline.payments.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code payments.payouts}. A null {@code version} means new (INSERT). */
@Table(schema = "payments", name = "payouts")
record PayoutRow(
        @Id String id,
        String merchantId,
        @Nullable String stripePayout,
        long amountCents,
        String kind,
        long feeCents,
        String state,
        Instant arrivesAt,
        Instant createdAt,
        int itemCount,
        @Nullable String payoutAccountId,
        @Nullable String destination,
        @Nullable String requestedBy,
        @Nullable String stripeFeeTransfer,
        @Version @Nullable Integer version) {}
