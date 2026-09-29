package ca.northline.payments.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code payments.payout_accounts}. */
@Table(schema = "payments", name = "payout_accounts")
record PayoutAccountRow(
        @Id String id,
        String merchantId,
        String method,
        String institutionName,
        @Nullable String institutionNumber,
        @Nullable String transitNumber,
        String last4,
        String holderName,
        String externalRef,
        String state,
        Instant createdAt,
        String createdBy,
        @Nullable Instant confirmedAt,
        @Nullable Instant effectiveAt,
        @Nullable Instant replacedAt,
        @Version @Nullable Integer version) {}
