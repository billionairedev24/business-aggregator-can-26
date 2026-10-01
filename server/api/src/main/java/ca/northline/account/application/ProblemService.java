package ca.northline.account.application;

import ca.northline.account.application.Problems.Card;
import ca.northline.account.application.Problems.Context;
import ca.northline.account.application.Problems.Item;
import ca.northline.account.application.Problems.Opened;
import ca.northline.account.application.Problems.Report;
import ca.northline.account.application.Problems.ReportProblems;
import ca.northline.account.application.Problems.Reported;
import ca.northline.account.domain.ProblemRules;
import ca.northline.booking.api.CustomerHistory;
import ca.northline.messaging.api.CustomerCaseDesk;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.payments.api.CustomerCaseQuery;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.CustomerEscrows;
import ca.northline.payments.api.CustomerEscrows.EscrowFacts;
import ca.northline.payments.api.SavedCards;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Something's wrong" (S-60). A report opens a refund case per thing reported (payments' {@code requestReview}: the
 * business reviews it for 24 h, then a Northline agent decides — never approved by the clock, never paid on the spot)
 * and one case in Northline's queue carrying the report, the photos and S-132's triage when the web asked for it.
 * Only money still inside its escrow window can be asked back ({@link ProblemRules}).
 */
@Service
@RequiredArgsConstructor
@Transactional
class ProblemService implements ReportProblems {

    private final CustomerOrders orders;
    private final CustomerHistory history;
    private final CustomerEscrows escrows;
    private final CustomerCases cases;
    private final CustomerCaseQuery caseQuery;
    private final CustomerCaseDesk desk;
    private final SavedCards cards;
    private final Businesses businesses;
    private final Clock clock;

    /** What was bought, before the escrow facts are known. */
    private record Thing(String ref, String title, int qty, long amountCents, String merchantId, String escrowRef) {}

    private record Subject(String kind, String id, @Nullable String ref, String title, Instant date, String escrowType,
            List<Thing> things) {}

    @Override
    @Transactional(readOnly = true)
    public Context context(String userId, String kind, String id) {
        var subject = subject(userId, kind, id);
        var items = items(userId, subject);
        var open = items.stream().filter(i -> "open".equals(i.status())).toList();
        var status = !open.isEmpty() ? "open"
                : items.stream().map(Item::status).min(Comparator.comparingInt(ProblemService::rank)).orElse("not_paid");
        var reportBy = open.stream().map(Item::reportBy).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        return new Context(
                subject.kind(), subject.id(), subject.ref(), subject.title(), subject.date(), items,
                ProblemRules.reasons(kind), status, reportBy, card(userId));
    }

    private static int rank(String status) {
        return List.of("reported", "not_yet", "closed", "not_paid").indexOf(status);
    }

    @Override
    public Reported report(String userId, Report r, Locale locale) {
        if (r.items().isEmpty()) {
            throw RuleViolation.of("items", "required", ProblemRules.ITEMS_REQUIRED);
        }
        if (!ProblemRules.reasons(r.kind()).contains(r.reason())) {
            throw RuleViolation.of("reason", "required", ProblemRules.REASON_REQUIRED);
        }
        var note = r.note() == null ? "" : r.note().strip();
        if (note.length() > ProblemRules.NOTE_MAX) {
            throw RuleViolation.of("note", "length", ProblemRules.NOTE_TOO_LONG);
        }
        var subject = subject(userId, r.kind(), r.id());
        var items = items(userId, subject).stream().collect(Collectors.toMap(Item::ref, i -> i, (a, _) -> a, LinkedHashMap::new));
        var chosen = new ArrayList<Item>();
        for (var ref : r.items().stream().distinct().toList()) {
            var item = items.get(ref);
            if (item == null) {
                throw RuleViolation.of("items", "allowed", ProblemRules.ITEMS_REQUIRED);
            }
            switch (item.status()) {
                case "open" -> chosen.add(item);
                case "reported" -> throw new Conflict("already_reported", ProblemRules.ALREADY_REPORTED);
                case "not_yet" -> throw new Conflict("not_fulfilled", ProblemRules.NOT_FULFILLED);
                case "closed" -> throw new Conflict("window_closed", ProblemRules.WINDOW_CLOSED);
                default -> throw new Conflict("not_paid", ProblemRules.NOT_PAID);
            }
        }
        var reasonWord = ProblemRules.REASON_WORDS.getOrDefault(r.reason(), r.reason());
        // one refund case per escrow: a goods order has one per line, a food order and a booking one in all
        var byEscrow = new LinkedHashMap<String, List<Item>>();
        var facts = facts(userId, subject);
        for (var item : chosen) {
            var thing = subject.things().stream().filter(t -> t.ref().equals(item.ref())).findFirst().orElseThrow();
            var escrow = Objects.requireNonNull(facts.get(thing.escrowRef()));
            byEscrow.computeIfAbsent(escrow.escrowId(), _ -> new ArrayList<>()).add(item);
        }
        var refunds = new ArrayList<String>();
        byEscrow.forEach((escrowId, list) -> {
            var escrow = facts.values().stream().filter(f -> f.escrowId().equals(escrowId)).findFirst().orElseThrow();
            var amount = Math.min(escrow.amountCents(), list.stream().mapToLong(Item::amountCents).sum());
            var titles = list.stream().map(i -> i.qty() > 1 ? i.title() + " ×" + i.qty() : i.title()).collect(Collectors.joining(", "));
            var what = clip(reasonWord + " · " + titles + (note.isEmpty() ? "" : " — " + note), 200);
            refunds.add(cases.requestReview(escrowId, userId, amount, what));
        });
        var category = r.triageCategory() != null && ProblemRules.TRIAGE_CATEGORIES.contains(r.triageCategory())
                ? r.triageCategory()
                : ProblemRules.category(r.kind(), r.reason());
        var label = switch (subject.kind()) {
            case "booking" -> "Booking " + Objects.requireNonNullElse(subject.ref(), subject.id());
            case "food" -> "Food order " + Objects.requireNonNullElse(subject.ref(), subject.id());
            default -> "Order " + Objects.requireNonNullElse(subject.ref(), subject.id());
        };
        var body = "Reported: " + reasonWord + "\n"
                + chosen.stream().map(i -> "- " + i.title() + (i.qty() > 1 ? " ×" + i.qty() : "") + " (" + i.merchantName() + ")")
                        .collect(Collectors.joining("\n"))
                + (note.isEmpty() ? "" : "\n\n" + note);
        var opened = desk.open(new CustomerCaseDesk.NewCase(
                userId,
                "refund",
                category,
                r.triageSummary() == null ? null : clip(r.triageSummary().strip(), 300),
                "safety".equals(category),
                subject.kind(),
                subject.id(),
                label,
                body,
                refunds,
                r.attachmentIds(),
                locale));
        var summaries = refunds.stream()
                .map(id -> caseQuery.find(userId, id).orElseThrow())
                .map(c -> new Opened(
                        c.id(), c.number(), c.amountCents(), c.taxCents(),
                        businesses.one(c.merchantId()).map(Businesses.Business::name).orElse(""), c.respondBy()))
                .toList();
        return new Reported(
                opened.id(),
                opened.code(),
                summaries,
                clock.instant(),
                summaries.stream().mapToLong(o -> o.amountCents() + o.taxCents()).sum(),
                card(userId));
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private @Nullable Card card(String userId) {
        return cards.defaultCard(userId).map(c -> new Card(c.brand(), c.last4())).orElse(null);
    }

    // ── what was bought ──────────────────────────────────────────────────────────────────────────────────────────────

    private Subject subject(String userId, String kind, String id) {
        return switch (kind) {
            case "order", "food" -> {
                var detail = orders.detail(userId, id).orElseThrow(() -> new NotFound("order", id));
                var food = "food".equals(detail.order().type());
                if (food != "food".equals(kind)) {
                    throw new NotFound("order", id);
                }
                var things = detail.lines().stream()
                        .map(l -> new Thing(l.id(), l.title(), l.qty(), l.amountCents(), l.merchantId(), food ? id : l.id()))
                        .toList();
                var date = Objects.requireNonNullElse(detail.order().deliveredAt(), detail.order().placedAt());
                yield new Subject(kind, id, detail.order().ref(), "", date, food ? "food_order" : "order_line", things);
            }
            case "booking" -> {
                var b = history.booking(userId, id).orElseThrow(() -> new NotFound("booking", id));
                yield new Subject(kind, id, b.ref(), b.title(), b.startsAt(), "booking",
                        List.of(new Thing(b.id(), b.title(), 1, 0, b.merchantId(), b.id())));
            }
            default -> throw new NotFound("problem", kind);
        };
    }

    private Map<String, EscrowFacts> facts(String userId, Subject s) {
        var refs = s.things().stream().map(Thing::escrowRef).distinct().toList();
        return escrows.of(userId, s.escrowType(), refs).stream()
                .collect(Collectors.toMap(EscrowFacts::refId, f -> f, (a, _) -> a));
    }

    private List<Item> items(String userId, Subject s) {
        var facts = facts(userId, s);
        var names = businesses.of(s.things().stream().map(Thing::merchantId).distinct().toList());
        var now = clock.instant();
        return s.things().stream().map(t -> {
            var f = facts.get(t.escrowRef());
            var name = names.containsKey(t.merchantId()) ? Objects.requireNonNull(names.get(t.merchantId())).name() : "";
            if (f == null) {
                return new Item(t.ref(), t.title(), t.qty(), t.amountCents(), 0, t.merchantId(), name, "not_paid", null);
            }
            // the booking's price is the escrow's; a food line's tax is its share of the order's
            var amount = "booking".equals(s.kind()) ? f.amountCents() : t.amountCents();
            var tax = f.amountCents() == 0 ? 0 : Math.round((double) f.taxCents() * amount / f.amountCents());
            var reportBy = "food".equals(s.kind()) && f.fulfilledAt() != null
                    ? f.fulfilledAt().plus(ProblemRules.FOOD_WINDOW)
                    : f.releaseAt();
            String status;
            if (f.openCase() || "disputed".equals(f.state())) {
                status = "reported";
            } else if ("refunded".equals(f.state())) {
                status = "closed";
            } else if (f.fulfilledAt() == null) {
                status = "not_yet";
            } else if ("food".equals(s.kind())) {
                status = reportBy != null && reportBy.isAfter(now) ? "open" : "closed";
            } else {
                status = "held".equals(f.state()) && reportBy != null && reportBy.isAfter(now) ? "open" : "closed";
            }
            return new Item(t.ref(), t.title(), t.qty(), amount, tax, t.merchantId(), name, status,
                    "open".equals(status) ? reportBy : null);
        }).toList();
    }
}
