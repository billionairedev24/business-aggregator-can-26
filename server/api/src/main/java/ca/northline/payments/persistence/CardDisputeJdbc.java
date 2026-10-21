package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.api.CardDisputes.Row;
import ca.northline.payments.application.CardDisputeStore;
import ca.northline.shared.MerchantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link CardDisputeStore} over {@code payments.disputes}, {@code card_dispute_evidence} and {@code dispute_fees}. */
@Repository
@RequiredArgsConstructor
class CardDisputeJdbc implements CardDisputeStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private static final String ROW = """
            select d.id, d.case_number, d.merchant_id, d.subject, d.amount_cents, d.stripe_dispute, d.stripe_status,
                   d.stripe_reason, d.stripe_evidence_due_by, d.respond_by, d.opened_at, d.decision,
                   e.submitted_at,
                   coalesce((select sum(f.fee_cents) from payments.dispute_fees f where f.dispute_id = d.id), 0) as fees
              from payments.disputes d
              left join payments.card_dispute_evidence e on e.dispute_id = d.id
             where d.stripe_dispute is not null""";

    private final JdbcClient jdbc;

    @Override
    public List<Row> list(MerchantScope scope, Instant closedSince, int limit) {
        return jdbc.sql(ROW + """
                           and (:everyone or d.merchant_id = any(:merchants))
                           and (d.decision is null or d.decided_at >= :since)
                         order by (d.decision is null) desc, coalesce(d.stripe_evidence_due_by, d.respond_by) asc nulls last,
                                  d.opened_at, d.id
                         limit :n
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(closedSince))
                .param("n", limit)
                .query(CardDisputeJdbc::row)
                .list();
    }

    @Override
    public Optional<Row> row(String disputeId) {
        return jdbc.sql(ROW + " and d.id = :id")
                .param("id", disputeId)
                .query(CardDisputeJdbc::row)
                .optional();
    }

    @Override
    public Optional<Evidence> evidence(String disputeId) {
        return jdbc.sql("""
                        select fields, files, updated_by, updated_at, submitted_at, submitted_by, last_error
                          from payments.card_dispute_evidence where dispute_id = :id
                        """)
                .param("id", disputeId)
                .query(CardDisputeJdbc::evidence)
                .optional();
    }

    @Override
    public Evidence lockEvidence(String disputeId) {
        jdbc.sql("insert into payments.card_dispute_evidence (dispute_id) values (:id) on conflict do nothing")
                .param("id", disputeId)
                .update();
        return jdbc.sql("""
                        select fields, files, updated_by, updated_at, submitted_at, submitted_by, last_error
                          from payments.card_dispute_evidence where dispute_id = :id for update
                        """)
                .param("id", disputeId)
                .query(CardDisputeJdbc::evidence)
                .single();
    }

    @Override
    public void saveDraft(
            String disputeId, Map<String, String> fields, Map<String, String> files, String staffId, Instant at) {
        jdbc.sql("""
                        update payments.card_dispute_evidence
                           set fields = cast(:fields as jsonb), files = cast(:files as jsonb), updated_by = :by,
                               updated_at = :at, last_error = null, version = version + 1
                         where dispute_id = :id
                        """)
                .param("fields", JSON.writeValueAsString(fields))
                .param("files", JSON.writeValueAsString(files))
                .param("by", staffId)
                .param("at", ts(at))
                .param("id", disputeId)
                .update();
    }

    @Override
    public void submitted(String disputeId, Map<String, String> stripeFiles, String staffId, String role, Instant at) {
        jdbc.sql("""
                        update payments.card_dispute_evidence
                           set submitted_at = :at, submitted_by = :by, submitted_role = :role,
                               stripe_files = cast(:files as jsonb), last_error = null, version = version + 1
                         where dispute_id = :id
                        """)
                .param("at", ts(at))
                .param("by", staffId)
                .param("role", role)
                .param("files", JSON.writeValueAsString(stripeFiles))
                .param("id", disputeId)
                .update();
    }

    @Override
    public void failed(String disputeId, String error) {
        jdbc.sql("update payments.card_dispute_evidence set last_error = :e where dispute_id = :id")
                .param("e", error.length() > 500 ? error.substring(0, 500) : error)
                .param("id", disputeId)
                .update();
    }

    @Override
    public void dueBy(String disputeId, @Nullable Instant dueBy) {
        if (dueBy == null) {
            return;
        }
        jdbc.sql("update payments.disputes set stripe_evidence_due_by = :due where id = :id")
                .param("due", ts(dueBy))
                .param("id", disputeId)
                .update();
    }

    @Override
    public boolean fee(String balanceTransaction, String disputeId, long feeCents, Instant at) {
        return jdbc.sql("""
                        insert into payments.dispute_fees (balance_transaction, dispute_id, fee_cents, at)
                        values (:txn, :dispute, :fee, :at) on conflict do nothing
                        """)
                        .param("txn", balanceTransaction)
                        .param("dispute", disputeId)
                        .param("fee", feeCents)
                        .param("at", ts(at))
                        .update()
                > 0;
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(
                rs.getString("id"),
                rs.getString("case_number"),
                rs.getString("merchant_id"),
                rs.getString("subject"),
                rs.getLong("amount_cents"),
                rs.getString("stripe_dispute"),
                rs.getString("stripe_status"),
                rs.getString("stripe_reason"),
                instant(rs, "stripe_evidence_due_by"),
                instant(rs, "respond_by"),
                requiredInstant(rs, "opened_at"),
                rs.getString("decision"),
                instant(rs, "submitted_at"),
                rs.getLong("fees"));
    }

    private static Evidence evidence(ResultSet rs, int i) throws SQLException {
        return new Evidence(
                map(rs.getString("fields")),
                map(rs.getString("files")),
                rs.getString("updated_by"),
                instant(rs, "updated_at"),
                instant(rs, "submitted_at"),
                rs.getString("submitted_by"),
                rs.getString("last_error"));
    }

    private static Map<String, String> map(@Nullable String json) {
        return json == null ? Map.of() : JSON.readValue(json, MAP);
    }
}
