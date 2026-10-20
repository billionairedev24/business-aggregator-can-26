package ca.northline.trust.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-152: the writers of {@code trust.points_ledger} besides checkout ({@link PointsWallet}): the loyalty module credits
 * what completed sales, provider-funded rewards and referrals earn, takes back refunds' share, and the expiry job
 * records what expired. Each reference moves points once (unique per person, type and reference), so listeners may
 * retry. Balances are computed first-to-expire spent first ({@code trust.domain.PointsBalance}).
 */
public interface PointsLedger {

    /** Credit types ({@code delta > 0}). */
    String EARN = "earn";

    String REWARD = "reward";
    String REFERRAL = "referral";

    /** Debit types. */
    String EARN_REVERSAL = "earn_reversal";

    String REWARD_REVERSAL = "reward_reversal";

    /**
     * Credits points once per {@code (refType, refId)}.
     *
     * @param refType {@link #EARN}, {@link #REWARD} or {@link #REFERRAL}
     * @param expiresAt when they expire (the region's rule), null = never
     * @param fundedBy the business paying for them (a provider-funded reward), else null (Northline)
     * @return false when that reference already credited
     */
    boolean credit(
            String userId,
            long points,
            String refType,
            String refId,
            @Nullable Instant expiresAt,
            @Nullable String fundedBy,
            String note);

    /**
     * Takes points back once per {@code (refType, refId)} — even when they were spent (the balance then pays it from
     * the next points earned).
     *
     * @param refType {@link #EARN_REVERSAL} or {@link #REWARD_REVERSAL}
     */
    boolean takeBack(String userId, long points, String refType, String refId, String note);

    /**
     * One row of the account's points activity.
     *
     * @param type {@code earn | reward | referral | redemption | redemption_return | refund_return | earn_reversal |
     *     reward_reversal | expiry} (seeded rows may carry {@code order} / {@code booking})
     * @param fundedBy the business that paid for provider-funded points
     */
    record Activity(
            String id,
            String type,
            long points,
            @Nullable String note,
            Instant at,
            @Nullable Instant expiresAt,
            @Nullable String fundedBy) {}

    /** Newest first, at most {@code limit}; the expiry job's zero rows are left out. */
    List<Activity> activity(String userId, int limit);

    /** The balance, and when the soonest points expire and how many (null / 0 when none expire). */
    record Summary(long balance, @Nullable Instant nextExpiryAt, long nextExpiryPoints) {}

    Summary summary(String userId);

    /**
     * The expiry job: records what each credit expired by {@code now} had left (once per credit), for at most
     * {@code people} people.
     *
     * @return how many people's points were checked
     */
    int expire(Instant now, int people);

    /** Points not yet spent or expired across everyone (the points liability), and how many people hold them. */
    record Outstanding(long points, long holders) {}

    Outstanding outstanding();
}
