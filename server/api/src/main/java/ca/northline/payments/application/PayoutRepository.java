package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: payouts, payout settings, bank accounts and the Stripe connected account. */
public interface PayoutRepository {

    /** The merchant's Stripe Connect Express account. */
    /**
     * The merchant's Stripe Connect Express account.
     *
     * @param payoutsEnabled false only when Stripe said so ({@code account.updated}); unknown counts as enabled
     */
    record ConnectedAccount(String merchantId, String stripeAccount, boolean instantPayouts, boolean payoutsEnabled) {}

    /** What {@code account.updated} says about a connected account. */
    record AccountStatus(
            String stripeAccount,
            @Nullable String merchantId,
            boolean chargesEnabled,
            boolean payoutsEnabled,
            @Nullable Boolean instantPayouts,
            int requirementsDue,
            int requirementsPastDue,
            @Nullable String disabledReason,
            Instant at) {}

    void insert(Payout payout);

    void update(Payout payout);

    Optional<Payout> byStripePayout(String stripePayout);

    /** Newest first. */
    List<Payout> history(String merchantId, int limit);

    List<Payout> inTransit(int limit);

    /** Whether a scheduled payout was already created in {@code [from, to)} (the scheduled run is idempotent). */
    boolean scheduledBetween(String merchantId, Instant from, Instant to);

    Optional<PayoutSchedule> schedule(String merchantId);

    void saveSchedule(String merchantId, PayoutSchedule schedule, String userId, Instant now);

    /** Merchants whose schedule has a payout day (not manual), for the scheduled run. */
    List<String> merchantsWithSchedules();

    void bankChanged(String merchantId, Instant at);

    Optional<ConnectedAccount> connectedAccount(String merchantId);

    /** Records the merchant's Connect account; false when it was already recorded. */
    boolean linkConnectedAccount(String merchantId, String stripeAccount);

    /**
     * Applies an {@code account.updated} (unless a newer one was applied already); records the account first when
     * Stripe's metadata names the merchant. Returns the merchant id when applied.
     */
    Optional<String> updateConnectedAccount(AccountStatus status);

    Optional<PayoutAccount> account(String merchantId, String accountId);

    Optional<PayoutAccount> activeAccount(String merchantId);

    Optional<PayoutAccount> pendingAccount(String merchantId);

    List<PayoutAccount> dueAccounts(Instant now);

    void insertAccount(PayoutAccount account);

    void updateAccount(PayoutAccount account);

    /** Escrows released since the previous payout (the "Jobs" column), after {@code since}. */
    int releasedSince(String merchantId, @Nullable Instant since);

    Optional<Instant> lastPayoutAt(String merchantId);
}
