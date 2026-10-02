package ca.northline.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The staff on-call rota ({@code identity.oncall_shifts}, S-96). */
public interface OncallRota {

    /** Shifts that overlap [from, to), by start. */
    List<Shift> between(Instant from, Instant to);

    Optional<Shift> shift(String id);

    Shift add(String userId, Instant startsAt, Instant endsAt, String duty, String createdBy);

    /** Hands the shift to {@code userId}. */
    Shift reassign(String id, String userId);

    void remove(String id);

    record Shift(String id, String userId, String name, Instant startsAt, Instant endsAt, String duty) {}
}
