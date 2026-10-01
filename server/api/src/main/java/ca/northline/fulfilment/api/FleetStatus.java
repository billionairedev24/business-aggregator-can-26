package ca.northline.fulfilment.api;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The courier fleet right now, for the console overview (S-91): who is out on a run, who is available, which runs are
 * stuck. Platform-wide: couriers and runs carry no market yet (zones get one with S-81/S-84).
 */
public interface FleetStatus {

    /**
     * @param stuckAfter a run is stuck when one of its stops is this late past its ETA and not reached
     */
    Fleet now(Instant now, Duration stuckAfter);

    /**
     * @param onRuns couriers on a run
     * @param active couriers not offline (available or on a run)
     * @param offlineOnRun couriers whose app went offline during a run that isn't done
     * @param stuckRuns runs with an overdue stop
     * @param oldestOverdue the ETA of the most overdue stop among them, or null
     */
    record Fleet(
            long onRuns,
            long active,
            long offlineOnRun,
            long stuckRuns,
            @Nullable Instant oldestOverdue) {}
}
