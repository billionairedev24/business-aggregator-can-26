package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.application.ReconciliationStore.Posting;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReconcileStripe}: Stripe's side from {@link StripeBalance} (charges, refunds, card disputes; transfers to
 * connected accounts and Stripe's own fees stay at Stripe and are left out, fees reported apart) and the payouts
 * Stripe accepted; the ledger's from {@link ReconciliationStore}. Objects are matched by their Stripe id; a ledger
 * posting without one, or a Stripe object without a posting, is a difference.
 */
@Service
@RequiredArgsConstructor
class StripeReconciliationService implements ReconcileStripe {

    static final int NOTE_MAX = 500;
    private static final Set<String> CHARGES = Set.of("charge", "payment");
    private static final Set<String> REFUNDS = Set.of("refund", "payment_refund");

    private final StripeBalance stripe;
    private final ReconciliationStore store;
    private final BusinessTime time;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    @Transactional
    public Day run(LocalDate day, @Nullable Actor actor) {
        var zone = time.platform();
        var from = day.atStartOfDay(zone).toInstant();
        var to = day.plusDays(1).atStartOfDay(zone).toInstant();
        var now = clock.instant();
        if (to.isAfter(now)) {
            throw RuleViolation.of("day", "range", FUTURE_DAY);
        }
        var items = new ArrayList<Item>();
        long stripeCents = 0;
        long fees = 0;
        var stripeById = new LinkedHashMap<String, Long>();
        var kindById = new HashMap<String, String>();
        for (var t : stripe.between(from, to)) {
            fees += t.feeCents();
            var kind = kind(t);
            if (kind == null) {
                continue; // transfers to connected accounts, Stripe's fees, …: still at Stripe
            }
            var id = t.sourceId() == null ? t.id() : t.sourceId();
            stripeById.merge(id, t.amountCents(), Long::sum);
            kindById.put(id, kind);
            stripeCents += t.amountCents();
        }
        for (var p : store.payouts(from, to)) {
            stripeById.merge(p.stripePayout(), -p.netCents(), Long::sum);
            kindById.put(p.stripePayout(), "payout");
            stripeCents -= p.netCents();
        }
        // the ledger's postings by Stripe object (one charge may hold several escrows)
        var ledgerById = new LinkedHashMap<String, List<Posting>>();
        long ledgerCents = 0;
        for (var p : store.postings(from, to)) {
            ledgerCents += p.cents();
            if (p.stripeId() == null) {
                items.add(new Item(
                        kind(p.refType()), null, null, p.refType(), p.refId(), p.cents(), "missing_at_stripe"));
            } else {
                ledgerById.computeIfAbsent(p.stripeId(), _ -> new ArrayList<>()).add(p);
            }
        }
        for (var e : stripeById.entrySet()) {
            var postings = ledgerById.remove(e.getKey());
            var kind = Objects.requireNonNullElse(kindById.get(e.getKey()), "other");
            if (postings == null) {
                items.add(new Item(kind, e.getKey(), e.getValue(), null, null, null, "missing_in_ledger"));
                continue;
            }
            var cents = postings.stream().mapToLong(Posting::cents).sum();
            var first = postings.getFirst();
            items.add(new Item(
                    kind,
                    e.getKey(),
                    e.getValue(),
                    first.refType(),
                    postings.size() == 1 ? first.refId() : first.refId() + " +" + (postings.size() - 1),
                    cents,
                    cents == e.getValue() ? "matched" : "amount_differs"));
        }
        ledgerById.forEach((stripeId, postings) -> items.add(new Item(
                kind(postings.getFirst().refType()),
                stripeId,
                null,
                postings.getFirst().refType(),
                postings.getFirst().refId(),
                postings.stream().mapToLong(Posting::cents).sum(),
                "missing_at_stripe")));
        var mismatches =
                (int) items.stream().filter(i -> !i.status().equals("matched")).count();
        var previous = store.day(day);
        var matched = mismatches == 0 && stripeCents == ledgerCents;
        var resolved = previous.filter(d -> d.status().equals("resolved")).filter(_ -> !matched);
        var result = new Day(
                day,
                stripeCents,
                ledgerCents,
                fees,
                items.size(),
                mismatches,
                matched ? "matched" : resolved.isPresent() ? "resolved" : "mismatch",
                now,
                resolved.map(Day::resolvedNote).orElse(null),
                resolved.map(Day::resolvedBy).orElse(null),
                resolved.map(Day::resolvedAt).orElse(null));
        store.save(result, from, to, items.stream().sorted(ORDER).toList());
        if (actor != null) {
            audit.record(new AuditTrail.Entry(
                    null,
                    actor.userId(),
                    actor.role(),
                    "payments.reconciliation_run",
                    "reconciliation_day",
                    day.toString(),
                    null,
                    Map.of(
                            "status",
                            result.status(),
                            "varianceCents",
                            result.varianceCents(),
                            "mismatches",
                            mismatches)));
        }
        return result;
    }

    private static final Comparator<Item> ORDER = Comparator.comparing(
                    (Item i) -> i.status().equals("matched"))
            .thenComparing(Item::kind)
            .thenComparing(i -> Objects.requireNonNullElse(i.stripeId(), ""));

    private static @Nullable String kind(StripeBalance.Txn t) {
        if (CHARGES.contains(t.type())) {
            return "charge";
        }
        if (REFUNDS.contains(t.type())) {
            return "refund";
        }
        var source = t.sourceId();
        if (t.type().equals("adjustment") && source != null && (source.startsWith("dp_") || source.startsWith("du_"))) {
            return "dispute";
        }
        return null;
    }

    private static String kind(String refType) {
        return switch (refType) {
            case "escrow", "order_delivery" -> "charge";
            case "refund" -> "refund";
            case "dispute" -> "dispute";
            case "payout" -> "payout";
            default -> "other";
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<Day> days(LocalDate from, LocalDate to) {
        return store.days(from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public DayDetail day(LocalDate day) {
        var d = store.day(day)
                .orElseThrow(() -> new ca.northline.shared.NotFound("reconciliation_day", day.toString()));
        return new DayDetail(d, store.items(day));
    }

    @Override
    @Transactional
    public Day resolve(LocalDate day, String note, Actor actor) {
        var text = note.strip();
        if (text.isEmpty()) {
            throw RuleViolation.of("note", "required", NOTE_REQUIRED);
        }
        if (text.length() > NOTE_MAX) {
            throw RuleViolation.of("note", "length", NOTE_LENGTH);
        }
        var d = store.day(day)
                .orElseThrow(() -> new ca.northline.shared.NotFound("reconciliation_day", day.toString()));
        if (!d.status().equals("mismatch")) {
            throw new Conflict("not_mismatched", NOT_MISMATCHED);
        }
        var now = clock.instant();
        store.resolve(day, text, actor.userId(), now);
        audit.record(new AuditTrail.Entry(
                null,
                actor.userId(),
                actor.role(),
                "payments.reconciliation_resolved",
                "reconciliation_day",
                day.toString(),
                Map.of("status", "mismatch"),
                Map.of("status", "resolved", "varianceCents", d.varianceCents())));
        return store.day(day).orElseThrow();
    }

    @Override
    @Transactional
    public String export(LocalDate from, LocalDate to, Actor actor) {
        var out = new StringBuilder(
                "day,status,stripe_cents,ledger_cents,variance_cents,fee_cents,kind,stripe_id,stripe_item_cents,"
                        + "ledger_ref_type,ledger_ref_id,ledger_item_cents,item_status,resolved_note\n");
        for (var d : store.days(from, to)) {
            var items = store.items(d.day()).stream()
                    .filter(i -> !i.status().equals("matched"))
                    .toList();
            if (items.isEmpty()) {
                out.append(row(d, null));
            }
            items.forEach(i -> out.append(row(d, i)));
        }
        audit.record(new AuditTrail.Entry(
                null,
                actor.userId(),
                actor.role(),
                "payments.reconciliation_exported",
                "reconciliation",
                from + ".." + to,
                null,
                null));
        return out.toString();
    }

    @Override
    @Transactional
    public String exportLedger(LocalDate from, LocalDate to, Actor actor) {
        var zone = time.platform();
        var out = new StringBuilder("at,account,debit_cents,credit_cents,ref_type,ref_id\n");
        store.ledger(
                        from.atStartOfDay(zone).toInstant(),
                        to.plusDays(1).atStartOfDay(zone).toInstant())
                .forEach(row -> out.append(String.join(
                                ",",
                                row.stream()
                                        .map(StripeReconciliationService::csv)
                                        .toList()))
                        .append('\n'));
        audit.record(new AuditTrail.Entry(
                null,
                actor.userId(),
                actor.role(),
                "payments.ledger_exported",
                "ledger",
                from + ".." + to,
                null,
                null));
        return out.toString();
    }

    private static String row(Day d, @Nullable Item i) {
        var cells = new ArrayList<String>(List.of(
                d.day().toString(),
                d.status(),
                Long.toString(d.stripeCents()),
                Long.toString(d.ledgerCents()),
                Long.toString(d.varianceCents()),
                Long.toString(d.feeCents())));
        cells.add(i == null ? "" : i.kind());
        cells.add(i == null ? "" : Objects.requireNonNullElse(i.stripeId(), ""));
        cells.add(i == null || i.stripeCents() == null ? "" : Long.toString(i.stripeCents()));
        cells.add(i == null ? "" : Objects.requireNonNullElse(i.ledgerRefType(), ""));
        cells.add(i == null ? "" : Objects.requireNonNullElse(i.ledgerRefId(), ""));
        cells.add(i == null || i.ledgerCents() == null ? "" : Long.toString(i.ledgerCents()));
        cells.add(i == null ? "" : i.status());
        cells.add(Objects.requireNonNullElse(d.resolvedNote(), ""));
        return String.join(
                        ",",
                        cells.stream().map(StripeReconciliationService::csv).toList()) + "\n";
    }

    /** RFC 4180 quoting, and a leading quote mark for cells a spreadsheet would read as a formula. */
    static String csv(String cell) {
        var value =
                !cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0 && !cell.matches("-?\\d+") ? "'" + cell : cell;
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
