package ca.northline.identity.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-152: households for Northline Plus and Invite a neighbour. Plus belongs to a household (up to 4 people share it);
 * the loyalty module owns the membership and its billing and mirrors the plan here, where the account area
 * ({@link PlusMemberships}) reads it.
 */
public interface Households {

    /** The person's household, created ("Home", the person as owner) when they have none. */
    String ensure(String userId);

    /** Whether the two people share a household (a referral between them earns nothing). */
    boolean shared(String userId, String otherUserId);

    /**
     * Mirrors the membership onto the household.
     *
     * @param plan {@code monthly | annual}, or {@code none} when it ended
     * @param renewsAt until when the benefits apply (the period's end, with the billing provider's grace)
     */
    void mirrorPlus(
            String householdId,
            String plan,
            @Nullable Instant since,
            @Nullable Instant renewsAt,
            @Nullable String subscriptionId);
}
