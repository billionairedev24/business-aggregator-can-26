package ca.northline.standing.application;

import ca.northline.standing.domain.Consequence;
import ca.northline.standing.domain.ConsequenceKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Outbound port: {@code standing.consequences}. */
public interface ConsequenceStore {

    /** False when an active consequence of that kind already exists for the subject (or the photo delay's booking). */
    boolean insert(Consequence consequence);

    Optional<Consequence> find(String id);

    /** The active consequence of {@code kind} of a business, or of a customer for a customer kind. */
    Optional<Consequence> active(String subjectId, ConsequenceKind kind);

    /** The photo delay of a booking (active or not). */
    Optional<Consequence> photoDelay(String bookingId);

    void lift(Consequence lifted);

    /** Newest first. */
    List<Consequence> ofBusiness(String merchantId, int limit);

    List<Consequence> ofCustomer(String customerId, int limit);

    /** Active consequences of {@code kind} whose {@code ends_at} passed. */
    List<Consequence> activeEndedBefore(ConsequenceKind kind, Instant at, int limit);

    /** Subjects (business or customer ids) whose {@code kind} consequence a person (not the rules) lifted since then. */
    Set<String> liftedByStaffSince(ConsequenceKind kind, Instant since);
}
