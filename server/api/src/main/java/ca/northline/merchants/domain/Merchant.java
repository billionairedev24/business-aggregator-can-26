package ca.northline.merchants.domain;

import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A business on Northline (aggregate root). State changes go through behaviour methods that enforce invariants and
 * return the domain event to publish; there are no setters. {@link #builder()} is for rehydration by the persistence
 * adapter and for tests.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Merchant {
    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    @ToString.Include
    private final MerchantType type;

    @ToString.Include
    private DisplayName displayName;

    private final @Nullable MerchantTier tier;
    private final @Nullable MerchantStatus status;
    private final @Nullable String city;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Changes the customer-facing name.
     *
     * @return the event to publish, or empty when the name did not change
     */
    public Optional<MerchantRenamed> rename(DisplayName newName, String actorId, Instant at) {
        if (newName.equals(displayName)) {
            return Optional.empty();
        }
        displayName = newName;
        updatedAt = at;
        return Optional.of(new MerchantRenamed(Ids.next(), at, id, actorId, newName.value()));
    }
}
