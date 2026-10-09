package ca.northline.orders.application;

import ca.northline.payments.api.CourierTips.Tip;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Tipping the courier after the delivery (mobile gaps part 2, design 01 B9): a delivered goods or food order brought by
 * a courier, within 7 days, once. 100 % goes to the courier.
 */
public interface TipCourier {

    /**
     * @param kind {@code amount} ({@code value} cents) | {@code percent} ({@code value} % of the goods or dishes)
     * @return the tip with the card payment to confirm ({@code clientSecret}; nothing to confirm with the fake gateway)
     */
    Started start(String customerId, String orderId, String kind, long value, @Nullable String clientKey);

    Tip confirm(String customerId, String orderId, String tipId);

    /** The order's tips (at checkout and after), and whether one can still be added. */
    Tips tips(String customerId, String orderId);

    /** @param provider {@code stripe} | {@code fake}; {@code publishableKey} for Stripe.js / the app's PaymentSheet */
    record Started(Tip tip, String provider, @Nullable String publishableKey) {}

    record Tips(List<Tip> items, boolean canTip, @Nullable String courierFirstName) {
        public Tips {
            items = List.copyOf(items);
        }
    }
}
