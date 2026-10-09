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

    Optional<PayoutSchedule> schedule(String merchantId);

    void saveSchedule(String merchantId, PayoutSchedule schedule, String userId, Instant now);

    /**
     * Engineering follow-ups (S-119 F6): what the minutely scheduled run needs about every business with a connected
     * account whose schedule has a payout day (not manual) — its schedule, its last scheduled payout and whether a bank
     * change is on hold — in one read, instead of three reads per business every minute.
     */
    List<ScheduledRunFacts> scheduledRunFacts();

    /**
     * @param schedule the business's schedule ({@link PayoutSchedule#DEFAULT} without settings)
     * @param lastScheduledAt when its latest scheduled payout was created, null when none was
     * @param pendingAccount a new bank account is in its 24 h hold
     */
    record ScheduledRunFacts(
            String merchantId,
            PayoutSchedule schedule,
            @Nullable Instant lastScheduledAt,
            boolean pendingAccount) {}

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

    /** Payout accounts linked from a Financial Connections account ({@code fca_…}), any state. */
    List<PayoutAccount> accountsLinkedTo(String financialConnectionsAccount);

    /** Escrows released since the previous payout (the "Jobs" column), after {@code since}. */
    int releasedSince(String merchantId, @Nullable Instant since);

    Optional<Instant> lastPayoutAt(String merchantId);
}
