package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-151): couriers' Stripe Connect accounts. Couriers are paid as recipients — an Express account
 * with only the {@code transfers} capability (they sell nothing), onboarded in Stripe's own pages, which collect their
 * identity and bank. Implemented by stripe-java when a key is set, by a local fake otherwise.
 */
public interface CourierAccountGateway {

    /** Creates the courier's Express account ({@code acct_…}); keyed by the courier, so a retry finds the same one. */
    String createAccount(String courierUserId);

    /** A single-use link to Stripe's onboarding for the account. */
    String onboardingLink(String stripeAccount, String returnUrl, String refreshUrl);

    /** The account as Stripe has it now. */
    Account account(String stripeAccount);

    /**
     * @param externalRef the default payout destination ({@code ba_…}), once Stripe collected a bank
     * @param bankLast4 its last four digits
     */
    record Account(
            boolean payoutsEnabled,
            int requirementsDue,
            @Nullable String externalRef,
            @Nullable String bankLast4) {}
}
