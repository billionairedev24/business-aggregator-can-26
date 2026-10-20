package ca.northline.region.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * S-152: the points earning rules as region data ({@code region.loyalty_rules}): the market's row, else its province's,
 * else the platform default. Code never asks "is this province X"; the loyalty module asks here with the business's
 * province and market ({@link MerchantPlaces}). The console's finance screen edits the rows (it audits; this writes).
 */
public interface LoyaltyRules {

    /** Kinds of sale that can earn. */
    Set<String> KINDS = Set.of("goods", "food", "service");

    /**
     * @param regionId the market or province row the rule belongs to; null for the platform default
     * @param regionName the region's English name ("Alberta", a city), null for the default
     * @param pointsPerDollar points per whole dollar of a completed sale (after discounts and points); 0 = none
     * @param kinds which sales earn ({@code goods}, {@code food}, {@code service})
     * @param expiryMonths points expire this many months after they were earned; null = never
     * @param referralPoints what each side of a referral gets; 0 = referrals pay nothing here
     * @param referralMonthlyCap rewarded referrals per referrer in a rolling 30 days
     * @param referralMinOrderCents the invitee's first completed order must be at least this much
     */
    record Rule(
            String id,
            @Nullable String regionId,
            @Nullable String regionName,
            int pointsPerDollar,
            Set<String> kinds,
            @Nullable Integer expiryMonths,
            int referralPoints,
            int referralMonthlyCap,
            long referralMinOrderCents,
            Instant updatedAt) {

        public Rule {
            kinds = Set.copyOf(kinds);
        }

        public boolean earns(String kind) {
            return pointsPerDollar > 0 && kinds.contains(kind);
        }
    }

    /** The rule that applies to a business in {@code province} / {@code marketId} (either may be null). */
    Rule of(@Nullable String province, @Nullable String marketId);

    /** Every row: the default first, then provinces and markets by name. */
    List<Rule> all();

    Optional<Rule> byRegion(@Nullable String regionId);

    /** Whether {@code regionId} names a province or market of the region model. */
    boolean regionExists(String regionId);

    /** A province or market a rule can belong to ({@code kind} {@code province | market}). */
    record RegionChoice(
            String id, String kind, String name, @Nullable String province) {}

    /** Every province and market, provinces first, by the region model's order. */
    List<RegionChoice> regions();

    /**
     * Inserts or replaces the region's row ({@code regionId} null = the default).
     *
     * @return the stored rule
     */
    Rule save(@Nullable String regionId, Change change, String actorId, Instant at);

    /** Removes a province's or market's own row (it falls back to its parent's); the default can't be removed. */
    boolean remove(String regionId);

    record Change(
            int pointsPerDollar,
            Set<String> kinds,
            @Nullable Integer expiryMonths,
            int referralPoints,
            int referralMonthlyCap,
            long referralMinOrderCents) {

        public Change {
            kinds = Set.copyOf(kinds);
        }
    }
}
