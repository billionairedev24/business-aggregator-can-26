package ca.northline.loyalty.application;

import ca.northline.loyalty.api.PlusBenefits;
import ca.northline.trust.api.PointsLedger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** S-152 inbound ports of the loyalty module. */
public final class LoyaltyUseCases {

    private LoyaltyUseCases() {}

    /** The account's points (web and app): balance, value, what expires next, the rules and the activity. */
    public interface ViewPoints {

        /**
         * @param pointsPerDollarSpent what a dollar of a completed sale earns where the person shops by default
         * @param redeemPointsPerDollar what $1 off costs at checkout
         * @param plusMultiplierPct a member's multiplier (100 when the person isn't one)
         */
        record MyPoints(
                long balance,
                long valueCents,
                @Nullable Instant nextExpiryAt,
                long nextExpiryPoints,
                int pointsPerDollarSpent,
                int redeemPointsPerDollar,
                @Nullable Integer expiryMonths,
                int plusMultiplierPct,
                List<PointsLedger.Activity> activity) {

            public MyPoints {
                activity = List.copyOf(activity);
            }
        }

        MyPoints points(String userId, int limit);
    }

    /** Invite a neighbour. */
    public interface Referrals {

        /**
         * @param shareUrl the consumer site with the code ({@code ?invite=}) to share
         * @param pointsEach what each side gets after the invitee's first completed order
         * @param minOrderCents the smallest first order that counts
         * @param claimed the code the person used when they joined, with its state; null when none
         */
        record ReferralView(
                String code,
                String shareUrl,
                long pointsEach,
                long minOrderCents,
                long pending,
                long rewarded,
                long pointsEarned,
                @Nullable Claimed claimed,
                boolean canClaim) {}

        /** @param state {@code pending | rewarded | refused} */
        record Claimed(
                String code,
                String state,
                Instant claimedAt,
                @Nullable String reason) {}

        ReferralView view(String userId);

        /** Uses a neighbour's code; 422 with the reason when it can't be used. */
        ReferralView claim(String userId, @Nullable String code);
    }

    /** Northline Plus in the account. */
    public interface ManagePlus {

        /**
         * @param status {@code none | incomplete | trialing | active | past_due | canceled}
         * @param provider {@code local | stripe}
         * @param payer the caller pays for it (only they can change it)
         * @param trialAvailable a new membership would start with the free trial
         * @param clientSecret the first payment needs the customer (3-D Secure): confirm with Stripe.js
         */
        record PlusView(
                String status,
                @Nullable String plan,
                String provider,
                @Nullable Instant since,
                @Nullable Instant currentPeriodEnd,
                boolean cancelAtPeriodEnd,
                boolean payer,
                int members,
                long monthlyPriceCents,
                long annualPriceCents,
                int trialDays,
                boolean trialAvailable,
                PlusBenefits.Benefits benefits,
                @Nullable String clientSecret,
                @Nullable String publishableKey) {}

        PlusView view(String userId);

        PlusView join(String userId, @Nullable String plan, String idempotencyKey);

        PlusView changePlan(String userId, @Nullable String plan, String idempotencyKey);

        /** Cancels at the end of what was paid for. */
        PlusView cancel(String userId, String idempotencyKey);

        /** Undoes a cancellation before the period ends. */
        PlusView resume(String userId, String idempotencyKey);
    }

    /** "Rewards near you" on the consumer home and the app. */
    public interface NearbyRewards {

        /**
         * @param type {@code provider | seller | kitchen | both}
         * @param slug the business page's slug, null without one
         * @param distanceKm from the point asked about, null without one
         */
        record NearbyReward(
                String merchantId,
                String businessName,
                String type,
                @Nullable String slug,
                int multiplier,
                @Nullable String label,
                LocalDate endsOn,
                @Nullable Double distanceKm) {}

        /** Running rewards of businesses in {@code city} or within {@code radiusKm} of the point, nearest first. */
        List<NearbyReward> near(@Nullable String city, @Nullable Double lat, @Nullable Double lng, int limit);
    }

    /** The console's earning rules (finance screen). */
    public interface ManageLoyaltyRules {

        record RuleChange(
                @Nullable Integer pointsPerDollar,
                @Nullable Set<String> kinds,
                @Nullable Integer expiryMonths,
                boolean neverExpire,
                @Nullable Integer referralPoints,
                @Nullable Integer referralMonthlyCap,
                @Nullable Long referralMinOrderCents) {}

        List<ca.northline.region.api.LoyaltyRules.Rule> rules();

        List<ca.northline.region.api.LoyaltyRules.RegionChoice> regions();

        /** @param regionId a province or market of the region model; null = the default */
        ca.northline.region.api.LoyaltyRules.Rule save(
                @Nullable String regionId, RuleChange change, String staffId, String role);

        void remove(String regionId, String staffId, String role);
    }

    /** The jobs: expiry, referral windows, the local fake's renewals. */
    public interface LoyaltyJobs {

        int expirePoints();

        int closeReferrals();

        int renewLocalMemberships();
    }
}
