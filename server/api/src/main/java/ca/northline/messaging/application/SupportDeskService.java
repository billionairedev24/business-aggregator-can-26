package ca.northline.messaging.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.messaging.application.SupportDeskStore.TicketRow;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.payments.api.AgentCases;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SupportDesk} (S-83). Replies go into the case's conversation as {@code agent} messages (what the requester
 * sees), take and escalate change the case row, a refund request waits for someone with the {@code refund} action;
 * every action writes the audit log in its transaction. An approved request decides the S-60 refund cases the case
 * points at that wait for an agent (the refund queue pays them — never instantly); otherwise it is the finance team's
 * instruction on record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SupportDeskService implements SupportDesk {

    static final int LIMIT = 500;
    static final Duration AT_RISK = Duration.ofMinutes(30);
    static final Duration WINDOW = Duration.ofDays(30);
    static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9.-]{1,59}");

    private final SupportDeskStore store;
    private final AttachmentStorage files;
    private final BusinessNames businesses;
    private final NotificationContacts contacts;
    private final DisputeDecisions decisions;
    private final AgentCases agentCases;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public Queue queue(MerchantScope scope, Filter filter, String staffId) {
        var now = clock.instant();
        var rows = store.open(scope, LIMIT);
        var names = new Names();
        var tickets = rows.stream().map(r -> ticket(r, names)).toList();
        var counts = new LinkedHashMap<String, Long>();
        for (var f : Filter.values()) {
            counts.put(
                    f.code(), tickets.stream().filter(matches(f, staffId, now)).count());
        }
        var figures = store.figures(scope, now.minus(WINDOW));
        var french = tickets.isEmpty()
                ? null
                : (double) tickets.stream().filter(t -> "fr".equals(t.lang())).count() / tickets.size();
        var kpis = new Kpis(
                tickets.size(),
                counts.getOrDefault(Filter.URGENT.code(), 0L),
                figures.medianFirstReplyMinutes(),
                counts.getOrDefault(Filter.SLA_RISK.code(), 0L),
                figures.resolvedWithoutEscalation(),
                figures.csat(),
                french);
        return new Queue(
                kpis,
                counts,
                tickets.stream().filter(matches(filter, staffId, now)).toList());
    }

    private static Predicate<Ticket> matches(Filter filter, String staffId, Instant now) {
        return t -> switch (filter) {
            case ALL -> true;
            case URGENT -> "urgent".equals(t.priority());
            case UNASSIGNED -> t.agentId() == null;
            case MINE -> staffId.equals(t.agentId());
            case SLA_RISK -> atRisk(t, now);
            case PROVIDERS -> "provider".equals(t.requesterType()) || "both".equals(t.requesterType());
            case SELLERS -> "seller".equals(t.requesterType()) || "both".equals(t.requesterType());
            case KITCHENS -> "kitchen".equals(t.requesterType());
            case CUSTOMERS -> "customer".equals(t.requesterType());
        };
    }

    /** Waiting for Northline (not for the requester) and the SLA ends within {@link #AT_RISK}. */
    private static boolean atRisk(Ticket t, Instant now) {
        var due = t.slaDueAt();
        return !"waiting".equals(t.state()) && due != null && due.isBefore(now.plus(AT_RISK));
    }

    @Override
    public TicketDetail ticket(String ticketId) {
        var row = store.find(ticketId).orElseThrow(() -> new NotFound("case", ticketId));
        return detail(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<File> attachment(String ticketId, String attachmentId) {
        return store.attachment(ticketId, attachmentId)
                .flatMap(f -> files.get(f.storageKey()).map(b -> new File(Bytes.of(b), f.contentType(), f.fileName())));
    }

    @Override
    @Transactional
    public TicketDetail reply(Reply c) {
        var row = store.lock(c.ticketId()).orElseThrow(() -> new NotFound("case", c.ticketId()));
        requireOpen(row);
        var body = c.body().strip();
        if (body.isEmpty()) {
            throw RuleViolation.of("body", "required", BODY_REQUIRED);
        }
        if (body.length() > BODY_MAX) {
            throw RuleViolation.of("body", "length", BODY_TOO_LONG);
        }
        var now = clock.instant();
        var thread = store.thread(row, now);
        store.message(thread, "agent", c.staffId(), shortName(c.staffId()), body, now);
        var state = c.resolve() ? "resolved" : "waiting";
        store.replied(row.id(), state, now);
        if (row.agentId() == null) {
            store.assign(row.id(), c.staffId(), shortName(c.staffId()), now);
        }
        record(
                row,
                c.staffId(),
                c.role(),
                c.resolve() ? "support.replied_resolved" : "support.replied",
                Map.of("state", row.state()),
                c.macroKey() == null ? Map.of("state", state) : Map.of("state", state, "macro", c.macroKey()));
        return ticket(row.id());
    }

    @Override
    @Transactional
    public TicketDetail take(String ticketId, String staffId, String role) {
        var row = store.lock(ticketId).orElseThrow(() -> new NotFound("case", ticketId));
        requireOpen(row);
        store.assign(row.id(), staffId, shortName(staffId), clock.instant());
        record(row, staffId, role, "support.assigned", null, Map.of("agent", staffId));
        return ticket(ticketId);
    }

    @Override
    @Transactional
    public TicketDetail escalate(String ticketId, @Nullable String note, String staffId, String role) {
        var row = store.lock(ticketId).orElseThrow(() -> new NotFound("case", ticketId));
        requireOpen(row);
        if (row.escalatedAt() != null) {
            throw new Conflict("already_escalated", ALREADY_ESCALATED);
        }
        var now = clock.instant();
        store.escalate(row.id(), staffId, now);
        var thread = store.thread(row, now);
        var text = "Escalated to trust & safety.";
        store.message(
                thread,
                "system",
                staffId,
                "Northline support",
                note == null || note.isBlank() ? text : text + " " + note.strip(),
                now);
        record(row, staffId, role, "support.escalated", null, Map.of("to", "trust_safety"));
        return ticket(ticketId);
    }

    @Override
    @Transactional
    public TicketDetail requestRefund(
            String ticketId, long amountCents, @Nullable String note, String staffId, String role) {
        var row = store.lock(ticketId).orElseThrow(() -> new NotFound("case", ticketId));
        requireOpen(row);
        if (amountCents <= 0) {
            throw RuleViolation.of("amountCents", "range", AMOUNT_RANGE);
        }
        var request = new RefundRequest(
                Ids.next(),
                amountCents,
                blankToNull(note),
                staffId,
                clock.instant(),
                "pending",
                null,
                null,
                null,
                null);
        store.insertRefundRequest(row.id(), request);
        record(
                row,
                staffId,
                role,
                "support.refund_requested",
                null,
                Map.of("request", request.id(), "amountCents", amountCents));
        return ticket(ticketId);
    }

    @Override
    @Transactional
    public TicketDetail decideRefund(
            String requestId, boolean approve, @Nullable String note, String staffId, String role) {
        var found = store.refundRequest(requestId).orElseThrow(() -> new NotFound("refund request", requestId));
        var ticketId = found.getKey();
        var request = found.getValue();
        if (!"pending".equals(request.state())) {
            throw new Conflict("request_closed", REQUEST_CLOSED);
        }
        if (request.requestedBy().equals(staffId)) {
            throw new Conflict("request_self", REQUEST_SELF);
        }
        var now = clock.instant();
        if (!store.decideRefundRequest(requestId, approve ? "approved" : "declined", staffId, blankToNull(note), now)) {
            throw new Conflict("request_closed", REQUEST_CLOSED);
        }
        var row = store.find(ticketId).orElseThrow();
        var decided = new ArrayList<String>();
        if (approve) {
            for (var refundId : refundIds(row.context())) {
                // Only refund cases escalated to an agent with nothing waiting for a co-sign; checked first, since an
                // exception from payments would roll the whole decision back.
                var waiting = agentCases
                        .detail("refund", refundId)
                        .filter(d -> "agent".equals(d.row().state()) && d.row().pending() == null)
                        .isPresent();
                if (waiting) {
                    decisions.decideRefund(refundId, true, staffId);
                    decided.add(refundId);
                } else {
                    log.info("Refund case {} of case {} isn't waiting for an agent", refundId, ticketId);
                }
            }
        }
        record(
                row,
                staffId,
                role,
                approve ? "support.refund_approved" : "support.refund_declined",
                Map.of("request", requestId, "state", "pending"),
                Map.of("state", approve ? "approved" : "declined", "refundCases", decided));
        return ticket(ticketId);
    }

    @Override
    public List<PendingRefund> pendingRefunds(MerchantScope scope) {
        var names = new Names();
        var out = new ArrayList<PendingRefund>();
        for (var e : store.pendingRefundRequests(scope, LIMIT)) {
            var request = e.getValue().named(names.person(e.getValue().requestedBy()));
            store.find(e.getKey()).ifPresent(row -> out.add(new PendingRefund(request, ticket(row, names))));
        }
        return out;
    }

    @Override
    public List<Macro> macros() {
        return store.macros();
    }

    @Override
    @Transactional
    public Macro saveMacro(@Nullable String id, MacroInput input, String staffId, String role) {
        var key = input.key().strip().toLowerCase(Locale.ROOT);
        var problems = new ArrayList<RuleViolation.Violation>();
        if (!KEY.matcher(key).matches()) {
            problems.add(new RuleViolation.Violation("key", "format", MACRO_KEY));
        }
        if (blank(input.title(), "en") || blank(input.title(), "fr")) {
            problems.add(new RuleViolation.Violation("title", "required", MACRO_TITLE));
        }
        if (blank(input.body(), "en") || blank(input.body(), "fr")) {
            problems.add(new RuleViolation.Violation("body", "required", MACRO_BODY));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        if (id != null && store.macro(id).isEmpty()) {
            throw new NotFound("macro", id);
        }
        if (store.macroKeyTaken(key, id)) {
            throw RuleViolation.of("key", "unique", MACRO_TAKEN);
        }
        var now = clock.instant();
        var macro = new Macro(
                id == null ? Ids.next() : id,
                key,
                Map.of("en", text(input.title(), "en"), "fr", text(input.title(), "fr")),
                Map.of("en", text(input.body(), "en"), "fr", text(input.body(), "fr")),
                now);
        store.saveMacro(macro, staffId, now);
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                id == null ? "support.macro_created" : "support.macro_updated",
                "macro",
                macro.id(),
                null,
                Map.of("key", key)));
        return macro;
    }

    @Override
    @Transactional
    public void deleteMacro(String id, String staffId, String role) {
        var macro = store.macro(id).orElseThrow(() -> new NotFound("macro", id));
        store.deleteMacro(id);
        audit.record(new AuditTrail.Entry(
                null, staffId, role, "support.macro_deleted", "macro", id, Map.of("key", macro.key()), null));
    }

    private TicketDetail detail(TicketRow row) {
        var names = new Names();
        var requests = store.refundRequests(row.id()).stream()
                .map(r -> r.named(names.person(r.requestedBy())))
                .toList();
        return new TicketDetail(ticket(row, names), row.context(), row.refLabel(), store.notes(row.id()), requests);
    }

    private Ticket ticket(TicketRow r, Names names) {
        var type = switch (r.requesterType()) {
            case "merchant" -> String.valueOf(r.context().getOrDefault("portal", "provider"));
            default -> r.requesterType();
        };
        var name = r.merchantId() != null
                ? names.business(r.merchantId())
                : r.requesterId() == null ? "—" : names.person(r.requesterId());
        return new Ticket(
                r.id(),
                SupportCase.code(r.number()),
                type,
                name,
                r.merchantId(),
                r.subject(),
                r.priority(),
                r.state(),
                r.agentId(),
                r.agentName(),
                r.createdAt(),
                r.slaDueAt(),
                r.lang(),
                r.escalatedAt() != null);
    }

    /** A business's or person's display name, read once per request. */
    private final class Names {
        private final Map<String, String> byId = new HashMap<>();

        String business(String merchantId) {
            return byId.computeIfAbsent(
                    "m:" + merchantId, _ -> businesses.displayName(merchantId).orElse(merchantId));
        }

        String person(String userId) {
            return byId.computeIfAbsent("u:" + userId, _ -> shortName(userId));
        }
    }

    /** "Dev K.", "R. Diaz" style: first name and the initial of the last word. */
    private String shortName(String userId) {
        var contact = contacts.contact(userId).orElse(null);
        if (contact == null) {
            return "—";
        }
        var parts = contact.displayName().strip().split("\\s+");
        return parts.length < 2 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    private void record(
            TicketRow row,
            String staffId,
            String role,
            String action,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(new AuditTrail.Entry(row.merchantId(), staffId, role, action, "ticket", row.id(), before, after));
    }

    private static void requireOpen(TicketRow row) {
        if ("resolved".equals(row.state())) {
            throw new Conflict("case_resolved", CASE_RESOLVED);
        }
    }

    private static List<String> refundIds(Map<String, Object> context) {
        return context.get("refunds") instanceof Collection<?> c
                ? c.stream().map(String::valueOf).toList()
                : List.of();
    }

    private static String text(Map<String, String> text, String lang) {
        return Objects.requireNonNullElse(text.get(lang), "").strip();
    }

    private static boolean blank(Map<String, String> text, String lang) {
        var v = text.get(lang);
        return v == null || v.isBlank();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
