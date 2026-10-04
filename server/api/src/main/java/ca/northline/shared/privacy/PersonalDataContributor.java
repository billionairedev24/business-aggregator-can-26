package ca.northline.shared.privacy;

import ca.northline.shared.CodedEnum;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A module's part in privacy requests (S-105: access, correction and erasure under PIPEDA and the provincial acts).
 * Every module that keeps personal data implements it as a Spring bean over its own schema (no cross-schema SQL); the
 * {@code privacy} module runs the requests and never reads another module's tables.
 *
 * <ul>
 *   <li>{@link #export}: what the module holds about the person, as sections of their access export.
 *   <li>{@link #erase}: forget the person. <b>Idempotent</b> — it runs again after a crash, a failure or a hold —
 *       and run in one transaction with the pipeline's step row, so it either happened or not. Data a law or the
 *       card networks make us keep is kept and pseudonymised (the person's id stays, their name, contact and words
 *       go) and reported as {@link Erasure#retained()}; data that can't be erased yet (an open order, an open
 *       dispute) is reported as {@link Erasure#held()} and the step is retried until it clears.
 *   <li>{@link #correct}: the fields people can't change themselves, corrected by staff on request.
 * </ul>
 */
public interface PersonalDataContributor {

    /** Run after every other module ({@link #order()}): identity blanks the person's name and contact last. */
    int LAST = 1000;

    /** The module's name: the erasure step's key and the export's section prefix ({@code orders}). */
    String module();

    /** Erasure order, lowest first. */
    default int order() {
        return 100;
    }

    /** The person's data in this module; an empty list when it holds nothing about them. */
    List<Section> export(Subject subject);

    /** Erases or pseudonymises the person's data; see the class comment. */
    Erasure erase(Subject subject);

    /** Field codes this module corrects on request ({@code phone}, {@code reviewName}). */
    default Set<String> correctable() {
        return Set.of();
    }

    /**
     * Applies a correction staff accepted. Throws {@link ca.northline.shared.RuleViolation} for a value the field
     * can't take, {@link IllegalArgumentException} for a field not in {@link #correctable()}.
     */
    default void correct(Subject subject, String field, String value) {
        throw new IllegalArgumentException("Not correctable here: " + field);
    }

    /**
     * Whom a request is about.
     *
     * @param email / {@code phone}: the account's contact when the request was made — rows that kept a contact
     *     without the id (invitations, the waitlist) are found by it, after identity has blanked the account
     */
    record Subject(
            String userId, @Nullable String email, @Nullable String phone, Locale locale) {}

    /**
     * One part of the export.
     *
     * @param key machine-readable name ({@code orders.orders})
     * @param json a JSON array of the rows, as the module renders it
     * @param records how many rows
     */
    record Section(String key, String titleEn, String titleFr, String json, int records) {

        public String title(Locale locale) {
            return locale.getLanguage().equals("fr") ? titleFr : titleEn;
        }
    }

    /** What a category of data is kept for, and why. */
    record Kept<R extends Enum<R> & CodedEnum>(String category, R reason) {}

    /**
     * The outcome of {@link #erase}.
     *
     * @param retained data kept (pseudonymised) by a retention duty
     * @param held data not erasable yet; the step is retried
     * @param merchantIds businesses whose public data changed (the search projection refreshes them)
     */
    record Erasure(List<Kept<Retention>> retained, List<Kept<Hold>> held, Set<String> merchantIds) {

        public Erasure {
            retained = List.copyOf(retained);
            held = List.copyOf(held);
            merchantIds = Set.copyOf(merchantIds);
        }

        public static Erasure done() {
            return new Erasure(List.of(), List.of(), Set.of());
        }

        public Erasure retaining(String category, Retention reason) {
            return new Erasure(
                    Stream.concat(retained.stream(), Stream.of(new Kept<>(category, reason)))
                            .toList(),
                    held,
                    merchantIds);
        }

        public Erasure holding(String category, Hold reason) {
            return new Erasure(
                    retained,
                    Stream.concat(held.stream(), Stream.of(new Kept<>(category, reason)))
                            .toList(),
                    merchantIds);
        }

        public Erasure touching(Set<String> merchants) {
            return new Erasure(
                    retained,
                    held,
                    Stream.concat(merchantIds.stream(), merchants.stream()).collect(Collectors.toSet()));
        }
    }

    /** Why data survives an erasure (pseudonymised: the id stays, the name, contact and free text go). */
    enum Retention implements CodedEnum {
        /** Sales and tax records: the Income Tax and Excise Tax Acts keep books six years. */
        TAX_RECORDS,
        /** Payments, escrows, refunds, payouts and the ledger. */
        FINANCIAL_RECORDS,
        /** Evidence for card chargebacks and disputes (card network rules). */
        CHARGEBACK_EVIDENCE,
        /** The business's own records of work done for it (who did a job, who handed off an order). */
        BUSINESS_RECORDS,
        /** Identity checks of a business's owners. */
        KYC_RECORDS,
        /** The append-only audit log (ids and codes only, seven years). */
        AUDIT_LOG,
        /**
         * S-108: proof of consent to commercial messages and of its withdrawal (CASL s. 13), three years after the
         * withdrawal (who, what, when, where, the wording; no network or browser evidence).
         */
        CONSENT_PROOF,
        /**
         * Age-restricted sales (2026-10-04): the ID checks recorded at handoffs — what a courier or team member
         * confirmed, never the ID — two years, as evidence of a lawful sale.
         */
        AGE_CHECK_RECORDS
    }

    /** What stops erasure for now; the step is retried until it clears. */
    enum Hold implements CodedEnum {
        OPEN_ORDER,
        UPCOMING_BOOKING,
        ESCROW_HELD,
        OPEN_DISPUTE,
        OPEN_REFUND,
        ACTIVE_DELIVERY,
        /** The person is the only owner of a business: ownership moves, or the business closes, first. */
        BUSINESS_OWNER
    }
}
