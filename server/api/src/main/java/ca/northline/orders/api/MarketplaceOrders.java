package ca.northline.orders.api;

import ca.northline.shared.MerchantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Platform-wide order figures for the console overview (S-91): goods and food orders of every business in the scope.
 * An order counts for a business when one of its lines is that business's.
 */
public interface MarketplaceOrders {

    /**
     * GMV of goods and food per period: {@code periods} consecutive periods of {@code length} from {@code start}, each
     * the line totals (quantity × unit price) of orders placed in it — cancelled orders and refunded lines left out.
     */
    List<Long> gmvCents(MerchantScope scope, Instant start, Duration length, int periods);

    /** Orders placed in [from, to), cancelled ones left out. */
    long placed(MerchantScope scope, Instant from, Instant to);

    /**
     * Orders delivered in [from, to) on a pooled run, and how many of them before their window's end.
     */
    OnTime onTime(MerchantScope scope, Instant from, Instant to);

    /** Average delivery fee customers paid on orders placed in [from, to) for delivery (zero fees included). */
    @Nullable
    Long averageDeliveryFeeCents(MerchantScope scope, Instant from, Instant to);

    /** Orders (not cancelled) on one pooled run ({@code orders.delivery_windows} id). */
    long onRun(MerchantScope scope, String windowId);

    record OnTime(long delivered, long onTime) {}
}
