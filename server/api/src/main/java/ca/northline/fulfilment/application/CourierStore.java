package ca.northline.fulfilment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: couriers and their shifts ({@code fulfilment.couriers}, {@code shifts}). */
public interface CourierStore {

    Optional<Courier> byUser(String userId);

    Optional<Courier> find(String courierId);

    /** Inserts; false when the person is already a courier. */
    boolean insert(Courier courier);

    List<Courier> inMarket(@Nullable String market);

    /**
     * Couriers of the market who can take a run at {@code at}: active, {@code available}, with a shift that is on —
     * longest since their last run first, then by id.
     */
    List<Courier> available(String market, Instant at);

    /** {@code available} → {@code on_run} if still available; false when someone else took them first. */
    boolean claim(String courierId, Instant at);

    /** Sets the status ({@code offline} | {@code available} | {@code on_run}). */
    void status(String courierId, String status);

    /** Pauses ({@code false}) or resumes ({@code true}) a courier: a paused courier is given no new run (S-81). */
    void active(String courierId, boolean active);

    void insertShift(Shift shift);

    Optional<Shift> shift(String shiftId);

    /** The courier's shifts that end after {@code from}, by start. */
    List<Shift> shifts(String courierId, Instant from);

    /** The courier's shift that is on. */
    Optional<Shift> onShift(String courierId);

    void startShift(String shiftId, Instant at);

    void endShift(String shiftId, Instant at);

    /**
     * @param status {@code offline} | {@code available} | {@code on_run}
     * @param vehicle {@code bike} | {@code ebike} | {@code car} | {@code van}
     */
    record Courier(
            String id,
            String userId,
            @Nullable String market,
            @Nullable String vehicle,
            String status,
            boolean active,
            @Nullable Instant lastAssignedAt) {}

    /** @param state {@code scheduled} | {@code on} | {@code done} | {@code cancelled} */
    record Shift(
            String id,
            String courierId,
            Instant startsAt,
            Instant endsAt,
            String state,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt) {}
}
