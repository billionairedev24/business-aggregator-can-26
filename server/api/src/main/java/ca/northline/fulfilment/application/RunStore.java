package ca.northline.fulfilment.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: courier runs and their stops ({@code fulfilment.runs}, {@code stops}). */
public interface RunStore {

    /** Serializes planning across replicas for the rest of the transaction. */
    void lockPlanning();

    /** The next free part number of a window's runs (1 when it has none). */
    int nextPart(String windowId);

    void insert(Run run, List<Stop> stops);

    /** Planned runs without a courier that start by {@code until}, soonest first. */
    List<Run> awaitingCourier(Instant until);

    /** The run, locked, when it still has no courier; empty when another transaction holds or assigned it. */
    Optional<Run> lockUnassigned(String runId);

    /** The run, locked. */
    Optional<Run> lock(String runId);

    Optional<Run> find(String runId);

    /** The courier's run that isn't done. */
    Optional<Run> openRunOf(String courierId);

    void assign(String runId, String courierId, Instant at);

    /** Takes the courier off a planned run (reassignment). */
    void unassign(String runId);

    void moveState(String runId, String state, Instant at);

    List<Stop> stops(String runId);

    /** Stops of several runs (ops view). */
    List<Stop> stops(Collection<String> runIds);

    Optional<Stop> stop(String stopId);

    void arrived(String stopId, Instant at);

    void pickedUp(String stopId, boolean scanOk, Instant at);

    void proofStored(String stopId, String kind, String key);

    void droppedOff(String stopId, String proofKind, Instant at);

    /**
     * The storage key of the photo a courier left as an order's proof of delivery: a done drop-off whose proof is a
     * photo still kept (S-107 retention clears it). Empty for a PIN or signature, before the drop-off, after retention.
     */
    Optional<String> proofPhotoKey(String orderId);

    /** Runs of a market starting in [{@code from}, {@code to}), by start (ops view). */
    List<Run> runs(@Nullable String market, Instant from, Instant to);

    /**
     * @param kind {@code pooled} | {@code direct}
     * @param state {@code planned} | {@code loading} | {@code en_route} | {@code done}
     * @param part 1, 2, … when a window's orders fill several runs
     */
    record Run(
            String id,
            String market,
            String kind,
            String state,
            @Nullable String windowId,
            @Nullable String label,
            int part,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Instant packBy,
            @Nullable String courierId,
            @Nullable Instant plannedAt,
            @Nullable Instant assignedAt,
            @Nullable Instant startedAt,
            @Nullable Instant doneAt,
            String heuristic) {}

    /**
     * @param kind {@code pickup} | {@code dropoff}
     * @param state {@code pending} | {@code arrived} | {@code done}
     * @param proofKey the object key of the photo / signature
     */
    record Stop(
            String id,
            String runId,
            String orderId,
            String kind,
            int seq,
            @Nullable String merchantId,
            @Nullable Instant eta,
            String state,
            @Nullable Instant arrivedAt,
            @Nullable Instant doneAt,
            @Nullable String proofKind,
            @Nullable String proofKey,
            @Nullable Boolean scanOk) {}
}
