package ca.northline.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The daily reconciliation of Stripe against the payments ledger (S-85, design 03 finance "Reconciliation · daily"):
 * for one day of the platform zone, the platform account's balance transactions (charges, refunds, card disputes) and
 * the payouts Stripe reported, object by object, against the ledger's cash at Stripe ({@code stripe_balance}). Run
 * nightly for yesterday and on demand by finance; a day that doesn't match is resolved by finance with a note.
 */
public interface ReconcileStripe {

    String NOTE_REQUIRED = "Say how the difference was resolved.";
    String NOTE_LENGTH = "Keep the note under 500 characters.";
    String NOT_MISMATCHED = "Only a day that doesn't match can be resolved.";
    String FUTURE_DAY = "Choose a day that has ended.";

    /** Recomputes the day (idempotent; a resolved day keeps its note while it still differs). */
    Day run(LocalDate day, @Nullable Actor actor);

    /** Days in [from, to], newest first; days never run are absent. */
    List<Day> days(LocalDate from, LocalDate to);

    DayDetail day(LocalDate day);

    Day resolve(LocalDate day, String note, Actor actor);

    /** CSV of the days and their differences in [from, to] (audited). */
    String export(LocalDate from, LocalDate to, Actor actor);

    /**
     * "Export to accounting": every ledger entry of [from, to] (platform-zone days) as CSV — time, account, debit,
     * credit, reference — ids and codes only (audited).
     */
    String exportLedger(LocalDate from, LocalDate to, Actor actor);

    /** @param role the console roles acted with */
    record Actor(String userId, String role) {}

    /**
     * @param stripeCents Σ Stripe amounts that day (charges +, refunds, disputes and payouts −)
     * @param ledgerCents Σ {@code stripe_balance} debits − credits that day
     * @param status {@code matched} | {@code mismatch} | {@code resolved}
     */
    record Day(
            LocalDate day,
            long stripeCents,
            long ledgerCents,
            long feeCents,
            int items,
            int mismatches,
            String status,
            Instant computedAt,
            @Nullable String resolvedNote,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt) {

        public long varianceCents() {
            return stripeCents - ledgerCents;
        }
    }

    /**
     * One object compared.
     *
     * @param kind {@code charge} | {@code refund} | {@code dispute} | {@code payout} | {@code other}
     * @param status {@code matched} | {@code missing_in_ledger} | {@code missing_at_stripe} | {@code amount_differs}
     */
    record Item(
            String kind,
            @Nullable String stripeId,
            @Nullable Long stripeCents,
            @Nullable String ledgerRefType,
            @Nullable String ledgerRefId,
            @Nullable Long ledgerCents,
            String status) {}

    /** @param items the differences first, then what matched */
    record DayDetail(Day day, List<Item> items) {
        public DayDetail {
            items = List.copyOf(items);
        }
    }
}
