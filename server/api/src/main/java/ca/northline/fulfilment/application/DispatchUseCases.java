package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Inbound ports of the fulfilment module (S-86): planning, the courier app and the console's dispatch view. */
public final class DispatchUseCases {
    private DispatchUseCases() {}

    /** Plans runs from the deliveries whose time has come (one planner at a time across replicas). */
    public interface PlanRuns {

        /** @param market only this market, or every market when null; returns the runs planned */
        int plan(@Nullable String market);
    }

    /** Gives the runs that need a courier now to couriers on shift; safe to run concurrently. */
    public interface AssignCouriers {

        /** @param market only this market, or every market when null; returns the runs assigned */
        int assign(@Nullable String market);
    }

    /** @param runs runs planned; {@code assigned} runs given a courier */
    public record Planned(int runs, int assigned) {}

    /** The courier app (S-87 builds it): shifts, the run, its stops, pickup and drop-off with proof. */
    public interface CourierApp {

        CourierView me(String userId);

        List<ShiftView> shifts(String userId);

        ShiftView startShift(String userId, String shiftId);

        ShiftView endShift(String userId, String shiftId);

        Optional<RunView> myRun(String userId);

        RunView arrive(String userId, String stopId);

        RunView pickUp(String userId, String stopId, boolean scanOk);

        /** @param kind {@code photo} | {@code signature} */
        RunView proof(String userId, String stopId, String kind, Bytes file);

        /**
         * @param proof {@code photo} | {@code signature} (uploaded first) | {@code pin} (the customer's 4 digits)
         * @param idCheck the courier's confirmations, required when the stop's order has age-restricted items
         */
        RunView dropOff(
                String userId, String stopId, String proof, @Nullable String pin, @Nullable IdCheckAnswer idCheck);

        /**
         * Age-restricted items: the order isn't handed over ({@code reason} one of {@code HandoffChecks.REASONS}); the
         * drop-off is done as refused and a stop back at the business is added to the run.
         */
        RunView refuse(String userId, String stopId, String reason);

        /** The refused order is back at the business ({@code return} stop). */
        RunView returned(String userId, String stopId);

        /**
         * S-88: the phone's position while on shift — kept only as the latest one, in Valkey; at most one per
         * {@code ping-interval} ({@link TooManyPings} otherwise). Customers whose order the courier carries see it.
         */
        Ping ping(String userId, double lat, double lng, @Nullable Double heading);
    }

    /** What the courier confirmed at the door of an age-restricted delivery (never the ID itself). */
    public record IdCheckAnswer(boolean idChecked, boolean recipientMatches, boolean ofAge) {}

    /**
     * An age-restricted drop-off: the age to check on government photo ID and whose name it must show (the account
     * holder: age-restricted items go only to the person who ordered them).
     */
    public record IdCheckView(int age, @Nullable String recipient) {}

    /** @param nextAfterMs when the app may send the next position */
    public record Ping(Instant acceptedAt, long nextAfterMs) {}

    /** The console's orders monitor and delivery ops (S-81 builds the screens; the contract is docs). */
    public interface DispatchConsole {

        List<RunSummary> runs(@Nullable String market, Instant from, Instant to);

        RunDetail run(String runId);

        DeliveryView delivery(String orderId);

        List<CourierSummary> couriers(@Nullable String market);

        CourierSummary addCourier(String userId, String market, String vehicle, Actor actor);

        ShiftView addShift(String courierId, Instant startsAt, Instant endsAt, Actor actor);

        RunSummary assign(String runId, String courierId, Actor actor);

        /**
         * S-81: stops giving the courier new runs (a run they have stays theirs; reassign it to move it), with the
         * dispatcher's reason in the audit log. Idempotent.
         */
        CourierSummary pause(String courierId, String reason, Actor actor);

        /** S-81: the courier can be given runs again. Idempotent. */
        CourierSummary resume(String courierId, Actor actor);

        /** Plans and assigns now, without waiting for the job. */
        Planned planNow(@Nullable String market, Actor actor);

        /**
         * The staff member acting, for the platform audit log ({@code developer.audit_log}, no business).
         *
         * @param role the console roles acted with ({@code CurrentStaff.roleCodes()})
         */
        record Actor(String userId, String role) {}
    }

    // ── views ───────────────────────────────────────────────────────────────────────────────────────────────────

    public record CourierView(
            String courierId,
            @Nullable String market,
            @Nullable String vehicle,
            String status,
            @Nullable ShiftView shift) {}

    public record ShiftView(
            String id,
            Instant startsAt,
            Instant endsAt,
            String state,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt) {}

    public record RunView(
            String id,
            @Nullable String label,
            int part,
            String kind,
            String state,
            String market,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            List<StopView> stops) {

        public RunView {
            stops = List.copyOf(stops);
        }
    }

    /**
     * One stop as the courier sees it.
     *
     * @param place the shop of a pickup
     * @param dropoff the customer's address and note, for a drop-off
     * @param packed a pickup's shop has packed this order
     * @param proofKind the proof given ({@code photo} | {@code signature} | {@code pin} | {@code id_refused}) or uploaded so far
     * @param idCheck the ID check an age-restricted drop-off needs; null otherwise
     */
    public record StopView(
            String id,
            int seq,
            String kind,
            String state,
            String orderId,
            @Nullable String orderRef,
            @Nullable Instant eta,
            @Nullable Instant arrivedAt,
            @Nullable Instant doneAt,
            @Nullable Place place,
            @Nullable Dropoff dropoff,
            boolean packed,
            @Nullable String proofKind,
            @Nullable IdCheckView idCheck) {}

    public record Place(
            String merchantId,
            String name,
            @Nullable String address,
            @Nullable Double lat,
            @Nullable Double lng) {}

    public record CourierRef(
            String id, String userId, @Nullable String name) {}

    /**
     * @param late a pending stop is more than 15 minutes past its ETA
     * @param nextEta the next pending stop's ETA
     */
    public record RunSummary(
            String id,
            @Nullable String label,
            int part,
            String market,
            String kind,
            String state,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Instant packBy,
            @Nullable CourierRef courier,
            int orders,
            int stopsDone,
            int stopsTotal,
            @Nullable Instant nextEta,
            boolean late,
            String heuristic) {}

    /** A run with its stops (ops: addresses are shown as the courier sees them). */
    public record RunDetail(RunSummary run, List<StopView> stops) {
        public RunDetail {
            stops = List.copyOf(stops);
        }
    }

    public record PickupView(
            String merchantId,
            @Nullable String name,
            @Nullable Instant packedAt,
            @Nullable Instant pickedUpAt) {}

    /** An order's delivery for the orders monitor. */
    public record DeliveryView(
            String orderId,
            @Nullable String orderRef,
            String orderType,
            String kind,
            String market,
            String state,
            @Nullable Instant orderBy,
            @Nullable Instant packBy,
            @Nullable RunSummary run,
            List<PickupView> pickups,
            @Nullable Instant dropoffEta,
            @Nullable Instant deliveredAt,
            @Nullable String proofKind) {

        public DeliveryView {
            pickups = List.copyOf(pickups);
        }
    }

    public record CourierSummary(
            String id,
            String userId,
            @Nullable String name,
            @Nullable String market,
            @Nullable String vehicle,
            String status,
            boolean active,
            @Nullable ShiftView shift,
            @Nullable String runId,
            ca.northline.fulfilment.api.CourierLocations.@Nullable Position position) {}
}
