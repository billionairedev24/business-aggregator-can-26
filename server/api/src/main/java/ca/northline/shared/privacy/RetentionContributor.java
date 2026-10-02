package ca.northline.shared.privacy;

import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A module's part in the retention schedule (S-107: the Privacy Policy's "How long we keep it"). The schedule — every
 * category's period, legal basis, action at expiry and owning module — is the {@code privacy} module's catalogue
 * ({@code privacy/retention-schedule.yml}); each module implements this over its own schema (no cross-schema SQL) for
 * the categories it owns, and the privacy module runs them every night, in batches, under one transaction per batch.
 *
 * <p>Legal holds are the same as the erasure pipeline's ({@link Hold}): a module reports what it knows ({@link
 * #holds}: open orders, upcoming bookings, held escrow, disputes and when they closed), the privacy module decides per
 * category which are still in force (a closed dispute keeps messages a year, the law of the person's province may keep
 * what was used to decide one) and hands every module the references it must not touch ({@link Run#held}).
 *
 * <ul>
 *   <li>{@link #expired}: how many rows of the category are past their period and not held — the dry run.
 *   <li>{@link #purge}: deletes, pseudonymises or aggregates up to {@link Run#batch()} of them, objects in storage
 *       included. <b>Idempotent</b>: a row already dealt with no longer matches, so a crash, a retry or two replicas
 *       running at once change nothing twice.
 * </ul>
 */
public interface RetentionContributor {

    /** The module's name, as in the catalogue's {@code module}. */
    String module();

    /** The catalogue categories this module carries out ({@code messaging.conversations}). */
    Set<String> categories();

    /** The legal holds this module knows about now (open orders, disputes …), over its own tables. */
    default List<HeldRef> holds(Instant now) {
        return List.of();
    }

    /**
     * Other names of held references, so that a module can match a hold made on a reference it doesn't use
     * (payments holds an {@code order_line}; orders knows its order, which messaging threads refer to).
     */
    default List<HeldRef> relate(Collection<HeldRef> holds) {
        return List.of();
    }

    /** Rows of the category past their period and not held (what a run would change). */
    long expired(Run run);

    /** Carries out the category's action on up to {@link Run#batch()} expired rows; returns how many. */
    long purge(Run run);

    /** Something another row refers to: a booking, an order, an order line, a dispute ({@code booking:01J…}). */
    record Ref(String type, String id) {

        public String key() {
            return type + ":" + id;
        }
    }

    /**
     * A legal hold on a reference.
     *
     * @param closedAt when the reason ended (a dispute decided); {@code null} while it is open
     * @param subjectId whom it is about, when a law of their province may keep it longer (a dispute's customer)
     */
    record HeldRef(Ref ref, Hold reason, @Nullable Instant closedAt, @Nullable String subjectId) {

        public static HeldRef open(String type, String id, Hold reason) {
            return new HeldRef(new Ref(type, id), reason, null, null);
        }

        /** The same hold on another name of the reference. */
        public HeldRef as(String type, String id) {
            return new HeldRef(new Ref(type, id), reason, closedAt, subjectId);
        }
    }

    /**
     * One batch of one category.
     *
     * @param cutoff rows whose clock started before this are past the period (now − the category's period)
     * @param held reference keys ({@link Ref#key()}) still under a hold for this category
     * @param heldSubjects people with an open privacy request: their data stays as it is until the request ends
     *     (PIPEDA s. 8(8) and the provincial acts: what a request is about is kept until the person has had their
     *     answer and their recourse)
     */
    record Run(
            String category,
            Instant now,
            Instant cutoff,
            int batch,
            boolean dryRun,
            Set<String> held,
            Set<String> heldSubjects) {

        public Run {
            held = Set.copyOf(held);
            heldSubjects = Set.copyOf(heldSubjects);
        }

        /** {@link #cutoff()} as a JDBC parameter. */
        public OffsetDateTime before() {
            return cutoff.atOffset(ZoneOffset.UTC);
        }

        /** {@link #now()} as a JDBC parameter. */
        public OffsetDateTime at() {
            return now.atOffset(ZoneOffset.UTC);
        }

        /** The held reference keys, as a SQL array parameter ({@code not (ref = any(:held))}). */
        public String[] heldKeys() {
            return held.toArray(String[]::new);
        }

        public String[] subjects() {
            return heldSubjects.toArray(String[]::new);
        }

        /** The same run with no hold at all: how many rows are past their period, held or not. */
        public Run ignoringHolds() {
            return new Run(category, now, cutoff, batch, dryRun, Set.of(), Set.of());
        }
    }
}
