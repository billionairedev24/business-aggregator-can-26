package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;

import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-107, payments.
 *
 * <ul>
 *   <li>{@code payments.dispute_evidence} — "Messages and dispute evidence: 2 years after the transaction, or until a
 *       dispute is closed plus 1 year, whichever is later": a decided dispute's statements and its evidence files
 *       (object storage, {@link DisputeEvidenceStorage}) go; the decision, amounts and dates stay with the financial
 *       records. The dispute's own hold carries its decision date, so the privacy module keeps it the year after (and
 *       longer where the customer's province's law asks).
 *   <li>{@code payments.financial_records} — "Transaction and tax records: 7 years": escrows, payments, refunds and
 *       disputes older than seven years lose the customer's id and name and the customer's words; amounts, tax and
 *       dates stay. The ledger and payouts hold no customer data.
 * </ul>
 *
 * Holds for every module: held escrow, open refunds and disputes (open, or decided with the decision date), on what
 * they are about (a booking, an order line, a food order) and on the dispute itself.
 */
@Component
@RequiredArgsConstructor
class PaymentsRetention implements RetentionContributor {

    static final String FINANCIAL = "payments.financial_records";
    static final String EVIDENCE = "payments.dispute_evidence";

    /** Decided disputes older than this can't hold anything any more (the law minimum is at most 10 years, V295). */
    private static final String DECIDED_WINDOW = "interval '10 years'";

    private static final String EVIDENCE_DUE = """
            from payments.disputes d left join payments.escrows e on e.id = d.ref_id
             where d.state = 'decided' and coalesce(e.occurred_at, d.opened_at) < :cutoff
               and (coalesce(d.evidence, '[]'::jsonb) <> '[]'::jsonb or d.customer_statement is not null
                    or d.response is not null or d.decision_note is not null)
               and not ('dispute:' || d.id = any(:held)) and not (coalesce(d.opened_by, '') = any(:subjects))
            """;
    private static final String ESCROWS = """
            from payments.escrows e
             where e.created_at < :cutoff and e.state in ('released', 'refunded')
               and (e.customer_id is not null or e.customer_name is not null)
               and not (coalesce(e.ref_type, '') || ':' || coalesce(e.ref_id, '') = any(:held)) and not (coalesce(e.customer_id, '') = any(:subjects))
            """;
    private static final String INTENTS = """
            from payments.payment_intents p
             where p.customer_id is not null and p.state in ('captured', 'refunded', 'failed', 'canceled')
               and exists (select 1 from payments.escrows e where e.payment_intent_id = p.id)
               and not exists (select 1 from payments.escrows e where e.payment_intent_id = p.id
                                  and (e.created_at >= :cutoff or e.state not in ('released', 'refunded')))
               and not (p.customer_id = any(:subjects))
            """;
    private static final String REFUNDS = """
            from payments.refunds r
             where r.created_at < :cutoff and r.state in ('denied', 'paid')
               and (r.customer_name is not null or r.what is not null or r.reason is not null
                    or r.contest_reason is not null)
            """;
    private static final String DISPUTES = """
            from payments.disputes d
             where d.state = 'decided' and d.opened_at < :cutoff
               and (d.customer_name is not null or d.opened_by is not null)
               and not ('dispute:' || d.id = any(:held)) and not (coalesce(d.opened_by, '') = any(:subjects))
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final DisputeEvidenceStorage evidence;

    @Override
    public String module() {
        return "payments";
    }

    @Override
    public Set<String> categories() {
        return Set.of(FINANCIAL, EVIDENCE);
    }

    @Override
    public List<HeldRef> holds(Instant now) {
        var holds = new ArrayList<HeldRef>();
        jdbc.sql("""
                        select d.id, d.state, d.decided_at, d.opened_by, e.ref_type, e.ref_id
                          from payments.disputes d left join payments.escrows e on e.id = d.ref_id
                         where d.state <> 'decided' or d.decided_at is null or d.decided_at > now() - %s
                        """.formatted(DECIDED_WINDOW))
                .query((rs, _) -> new DisputeRow(
                        rs.getString("id"),
                        "decided".equals(rs.getString("state")) ? instant(rs, "decided_at") : null,
                        rs.getString("opened_by"),
                        rs.getString("ref_type"),
                        rs.getString("ref_id")))
                .list()
                .forEach(d -> {
                    var hold = new HeldRef(new Ref("dispute", d.id()), Hold.OPEN_DISPUTE, d.decidedAt(), d.openedBy());
                    holds.add(hold);
                    var type = d.refType();
                    var ref = d.refId();
                    if (type != null && ref != null) {
                        holds.add(hold.as(type, ref));
                    }
                });
        jdbc.sql("select ref_type, ref_id from payments.escrows where state = 'held' and ref_id is not null")
                .query((rs, _) -> HeldRef.open(rs.getString("ref_type"), rs.getString("ref_id"), Hold.ESCROW_HELD))
                .list()
                .forEach(holds::add);
        jdbc.sql("""
                        select case when booking_id is not null then 'booking' else 'order_line' end as type,
                               coalesce(booking_id, order_line_id) as id
                          from payments.refunds
                         where state in ('requested', 'seller_review', 'agent_review', 'approved')
                           and coalesce(booking_id, order_line_id) is not null
                        """)
                .query((rs, _) -> HeldRef.open(rs.getString("type"), rs.getString("id"), Hold.OPEN_REFUND))
                .list()
                .forEach(holds::add);
        return holds;
    }

    private record DisputeRow(
            String id,
            @Nullable Instant decidedAt,
            @Nullable String openedBy,
            @Nullable String refType,
            @Nullable String refId) {}

    @Override
    public long expired(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case EVIDENCE -> count(EVIDENCE_DUE, p);
            case FINANCIAL ->
                count(ESCROWS, p) + count(INTENTS, p) + count(REFUNDS, p) + count(DISPUTES, p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    private long count(String from, Map<String, Object> p) {
        return jdbc.sql("select count(*) " + from).params(p).query(Long.class).single();
    }

    @Override
    public long purge(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case EVIDENCE -> evidence(p);
            case FINANCIAL -> financial(p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    /** Statements blanked and files deleted; a file already gone is fine (idempotent). */
    private long evidence(Map<String, Object> p) {
        var due = jdbc.sql("select d.id, coalesce(d.evidence, '[]'::jsonb)::text as evidence "
                        + EVIDENCE_DUE + " order by d.decided_at limit :batch")
                .params(p)
                .query((rs, _) -> Map.entry(rs.getString("id"), rs.getString("evidence")))
                .list();
        for (var dispute : due) {
            for (var key : storageKeys(dispute.getValue())) {
                evidence.delete(key);
            }
            jdbc.sql("""
                            update payments.disputes
                               set evidence = '[]'::jsonb, customer_statement = null, response = null,
                                   decision_note = null, version = version + 1
                             where id = :id
                            """)
                    .param("id", dispute.getKey())
                    .update();
        }
        return due.size();
    }

    private List<String> storageKeys(String evidenceJson) {
        var keys = new ArrayList<String>();
        for (JsonNode item : json.readTree(evidenceJson)) {
            var key = text(item.get("storageKey"));
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static @Nullable String text(@Nullable JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private long financial(Map<String, Object> p) {
        var done = jdbc.sql("""
                        update payments.escrows set customer_id = null, customer_name = null, version = version + 1
                         where id in (select e.id %s limit :batch)
                        """.formatted(ESCROWS))
                .params(p)
                .update();
        done += jdbc.sql("update payments.payment_intents set customer_id = null where id in (select p.id %s limit :batch)"
                        .formatted(INTENTS))
                .params(p)
                .update();
        done += jdbc.sql("""
                        update payments.refunds
                           set customer_name = null, what = null, reason = null, contest_reason = null,
                               version = version + 1
                         where id in (select r.id %s limit :batch)
                        """.formatted(REFUNDS))
                .params(p)
                .update();
        done += jdbc.sql("""
                        update payments.disputes set customer_name = null, opened_by = null, version = version + 1
                         where id in (select d.id %s limit :batch)
                        """.formatted(DISPUTES))
                .params(p)
                .update();
        return done;
    }

    private static Map<String, Object> params(Run run) {
        return Map.of(
                "cutoff", run.before(), "held", run.heldKeys(), "subjects", run.subjects(), "batch", run.batch());
    }
}
