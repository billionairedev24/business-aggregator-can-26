package ca.northline.fulfilment.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Which orders' deliveries are behind (S-81, the console's orders monitor): their run has an overdue stop. */
public interface DeliveryAlerts {

    /**
     * Of {@code orderIds}, those on a run that isn't done with a pending stop more than {@code after} past its ETA at
     * {@code now}.
     */
    Set<String> stuck(Collection<String> orderIds, Instant now, Duration after);
}
