package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-151): {@code payments.courier_accounts} and {@code payments.courier_payouts}. */
public interface CourierPayoutStore {

    Optional<CourierAccount> account(String courierUserId);

    Optional<CourierAccount> byStripeAccount(String stripeAccount);

    /** Inserts the courier's account unless they have one; false when they did. */
    boolean insert(CourierAccount account);

    /** Stripe's view of the account, unless {@code at} is older than the last one applied. */
    void updateAccount(String stripeAccount, CourierAccountGateway.Account status, Instant at);

    /** Couriers whose account can receive payouts. */
    List<CourierAccount> payable();

    void insertPayout(CourierPayout payout);

    void updatePayout(CourierPayout payout);

    Optional<CourierPayout> payout(String id);

    Optional<CourierPayout> byStripePayout(String stripePayout);

    /** In transit and expected by {@code before} (the reconciler for payouts whose webhook never came). */
    List<CourierPayout> arrivedBy(Instant before, int limit);

    List<CourierPayout> payouts(String courierUserId, int limit);

    record CourierAccount(
            String userId,
            String stripeAccount,
            boolean payoutsEnabled,
            int requirementsDue,
            @Nullable String externalRef,
            @Nullable String bankLast4,
            Instant createdAt) {}

    /** @param state {@code pending} | {@code in_transit} | {@code paid} | {@code failed} | {@code canceled} */
    record CourierPayout(
            String id,
            String userId,
            String stripeAccount,
            long amountCents,
            @Nullable String stripeTransfer,
            @Nullable String stripePayout,
            String state,
            @Nullable Instant arrivesAt,
            @Nullable String failure,
            Instant createdAt) {

        public CourierPayout with(
                String state,
                @Nullable String stripeTransfer,
                @Nullable String stripePayout,
                @Nullable Instant arrivesAt,
                @Nullable String failure) {
            return new CourierPayout(
                    id,
                    userId,
                    stripeAccount,
                    amountCents,
                    stripeTransfer,
                    stripePayout,
                    state,
                    arrivesAt,
                    failure,
                    createdAt);
        }
    }
}
