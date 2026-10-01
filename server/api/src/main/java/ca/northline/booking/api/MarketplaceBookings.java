package ca.northline.booking.api;

import ca.northline.shared.MerchantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Platform-wide booking figures for the console overview (S-91): service jobs of every business in the scope. */
public interface MarketplaceBookings {

    /**
     * GMV of services per period: {@code periods} consecutive periods of {@code length} from {@code start}, each the
     * price of the bookings made (created) in it, cancelled ones left out.
     */
    List<Long> gmvCents(MerchantScope scope, Instant start, Duration length, int periods);

    /** Bookings made in [from, to), cancelled ones left out. */
    long made(MerchantScope scope, Instant from, Instant to);

    /** Providers on a job now: distinct team members (else businesses) with a job en route or on site. */
    long providersOnJobs(MerchantScope scope);
}
