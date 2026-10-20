package ca.northline.fulfilment.web;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where Stripe sends a courier back after payout onboarding (S-151): the consumer site's {@code /courier/payouts},
 * which the courier app opens as a Universal / App Link (S-87: the courier app's links live on the consumer site). The
 * app may ask for a page under it; anything else is replaced by it, so the endpoint can't be used as an open redirect.
 */
@Component
class CourierPayoutLinks {

    private final String base;

    CourierPayoutLinks(@Value("${northline.fulfilment.courier-payout-return}") String base) {
        this.base = base;
    }

    String returnUrl(@Nullable String asked) {
        return asked != null && (asked.equals(base) || asked.startsWith(base + "?") || asked.startsWith(base + "/"))
                ? asked
                : base;
    }
}
