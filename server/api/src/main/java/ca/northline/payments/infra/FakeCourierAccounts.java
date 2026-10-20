package ca.northline.payments.infra;

import ca.northline.payments.application.CourierAccountGateway;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in for couriers' Stripe accounts when no Stripe key is configured (local, test, CI; S-151). An account exists
 * as soon as it is created; asking for its onboarding link counts as finishing onboarding (Stripe's pages can't be
 * shown here), so it is then enabled for payouts with a fake bank ending 6789. The link sends the courier straight
 * back to the app's return URL.
 */
@Slf4j
class FakeCourierAccounts implements CourierAccountGateway {

    private final Map<String, String> byCourier = new ConcurrentHashMap<>();
    private final Set<String> onboarded = ConcurrentHashMap.newKeySet();

    @Override
    public String createAccount(String courierUserId) {
        return byCourier.computeIfAbsent(
                courierUserId, id -> "acct_fake_courier_" + id.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public String onboardingLink(String stripeAccount, String returnUrl, String refreshUrl) {
        onboarded.add(stripeAccount);
        log.info("FAKE STRIPE courier onboarding of {} counts as done", stripeAccount);
        return returnUrl + (returnUrl.contains("?") ? "&" : "?") + "fake_onboarding=done";
    }

    @Override
    public Account account(String stripeAccount) {
        return onboarded.contains(stripeAccount)
                ? new Account(true, 0, "ba_fake_" + Integer.toUnsignedString(stripeAccount.hashCode()), "6789")
                : new Account(false, 3, null, null);
    }
}
