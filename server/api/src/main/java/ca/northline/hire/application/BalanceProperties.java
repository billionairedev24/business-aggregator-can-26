package ca.northline.hire.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.booking.balance.*} (S-153): the balance of a deposit quote.
 *
 * @param lead how long before the event it is held ({@code BOOKING_BALANCE_LEAD}; design 06: "balance held 48 h before
 *     the event")
 * @param retryEvery the wait after a refused attempt ({@code BOOKING_BALANCE_RETRY_EVERY})
 * @param attempts attempts before it stays {@code failed} and the provider decides ({@code BOOKING_BALANCE_ATTEMPTS})
 * @param interval how often the job looks for due balances ({@code BOOKING_BALANCE_JOB_INTERVAL})
 */
@ConfigurationProperties("northline.booking.balance")
public record BalanceProperties(
        @DefaultValue("PT48H") Duration lead,
        @DefaultValue("PT6H") Duration retryEvery,
        @DefaultValue("3") int attempts,
        @DefaultValue("PT5M") Duration interval) {

    public int leadHours() {
        return (int) lead.toHours();
    }
}
