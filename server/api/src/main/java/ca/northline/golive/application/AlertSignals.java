package ca.northline.golive.application;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the alerting side of the checklist (S-113's rules deployed, nothing paging now). Adapter by
 * configuration: none (no metrics backend — the two gates are recorded by hand) or a Prometheus-compatible HTTP API.
 */
public interface AlertSignals {

    /** Never throws: a value that couldn't be read is null. */
    Reading read();

    /**
     * @param configured a metrics backend is configured at all
     * @param alertingRules Northline alerting rules the backend evaluates, null when unreadable
     * @param firing paging alerts firing now, null when unreadable
     * @param firingNames their names (at most 10)
     */
    record Reading(
            boolean configured,
            @Nullable Integer alertingRules,
            @Nullable Integer firing,
            List<String> firingNames) {

        public static final Reading OFF = new Reading(false, null, null, List.of());

        public Reading {
            firingNames = List.copyOf(firingNames);
        }
    }
}
