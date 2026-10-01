package ca.northline.console.application;

import ca.northline.shared.CodedEnum;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the platform's live health for the console overview (S-91, design 03 "System health"), from the
 * metrics S-111 exports. Adapter by {@code northline.console.health.provider}: {@code none} (local, test, or no metrics
 * backend — every signal {@code unknown}) or {@code prometheus} (PromQL over the HTTP API of any Prometheus-compatible
 * store: Grafana Cloud / Mimir, Amazon / Google / Azure managed Prometheus, a self-hosted Prometheus).
 */
public interface HealthSignals {

    /** The tiles, in design order. {@link #COURIER_APP} comes from the fleet, not from here. */
    enum Signal implements CodedEnum {
        /** p95 of the api's requests, ms. */
        API_P95,
        /** p95 of the consumer search endpoints, ms. */
        SEARCH_P95,
        /** Kafka consumer lag, records (max over the worker's consumers). */
        KAFKA_LAG,
        /** Stripe: the share of failed calls to Stripe over 5 minutes (0–1). */
        STRIPE,
        /** Open live order-tracking streams. */
        TRACKING_STREAMS,
        /** Couriers whose app went offline mid-run (fleet). */
        COURIER_APP
    }

    enum Status implements CodedEnum {
        OK,
        DEGRADED,
        UNKNOWN
    }

    /** @param value the measurement in the signal's unit, null when it couldn't be read */
    record Reading(Signal signal, @Nullable Double value, Status status) {

        public static Reading unknown(Signal signal) {
            return new Reading(signal, null, Status.UNKNOWN);
        }
    }

    /** Every signal but {@link Signal#COURIER_APP}, in order; never throws (a failed read is {@code unknown}). */
    List<Reading> read();
}
