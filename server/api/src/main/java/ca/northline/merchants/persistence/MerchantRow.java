package ca.northline.merchants.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code merchants.merchants} (only the columns this module maps so far; unmapped columns are left untouched
 * by UPDATEs). Enum columns stay {@code String} — {@link MerchantRowMapper} converts via {@code CodedEnums}.
 */
@Table(schema = "merchants", name = "merchants")
record MerchantRow(
        @Id String id,
        String type,
        String displayName,
        @Nullable String tier,
        @Nullable String status,
        @Nullable String city,
        Instant createdAt,
        Instant updatedAt) {}
