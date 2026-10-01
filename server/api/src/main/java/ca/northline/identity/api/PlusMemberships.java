package ca.northline.identity.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Northline Plus as the account area reads it (S-58): the household the person belongs to and its plan
 * ({@code identity.households.plus_plan}). Nothing bills Plus yet (no subscription backend; DECISIONS S-58/S-59).
 */
public interface PlusMemberships {

    /**
     * @param plan {@code monthly | annual}
     * @param renewsAt the next renewal (or the end of the free trial), when known
     * @param members people sharing the household (the person included)
     */
    record Plus(
            String householdId,
            String plan,
            @Nullable Instant since,
            @Nullable Instant renewsAt,
            int members) {}

    /** The person's active Plus membership, through their household; empty when the household has none. */
    Optional<Plus> of(String userId);
}
