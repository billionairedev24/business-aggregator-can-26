package ca.northline.payments.api;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-120: where each business's Stripe Connect account stands, from the latest {@code account.updated} Stripe sent
 * (S-12) — for the console's pilot onboarding board. Nothing is fetched from Stripe here.
 */
public interface ConnectReadiness {

    /** Businesses without a connected account are absent. */
    Map<String, Account> of(Collection<String> merchantIds);

    /**
     * @param chargesEnabled null until Stripe has reported the account
     * @param payoutsEnabled null until Stripe has reported the account
     * @param disabledReason Stripe's {@code requirements.disabled_reason}, e.g. {@code requirements.past_due}
     */
    record Account(
            String stripeAccount,
            @Nullable Boolean chargesEnabled,
            @Nullable Boolean payoutsEnabled,
            int requirementsDue,
            int requirementsPastDue,
            @Nullable String disabledReason) {

        /** Stripe said the account can take card payments and receive payouts. */
        public boolean ready() {
            return Boolean.TRUE.equals(chargesEnabled) && Boolean.TRUE.equals(payoutsEnabled);
        }

        /** Stripe hasn't reported on the account yet. */
        public boolean unreported() {
            return chargesEnabled == null && payoutsEnabled == null;
        }
    }
}
