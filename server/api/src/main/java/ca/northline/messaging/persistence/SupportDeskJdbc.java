package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.JSON;
import static ca.northline.messaging.persistence.MessagingSql.instant;
import static ca.northline.messaging.persistence.MessagingSql.json;
import static ca.northline.messaging.persistence.MessagingSql.requiredInstant;
import static ca.northline.messaging.persistence.MessagingSql.ts;

import ca.northline.messaging.application.SupportDesk.Macro;
import ca.northline.messaging.application.SupportDesk.Note;
import ca.northline.messaging.application.SupportDesk.RefundRequest;
import ca.northline.messaging.application.SupportDeskStore;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/**
 * The support desk over {@code messaging.tickets}, the case conversations ({@code messaging.threads} of kind
 * {@code case}), {@code messaging.support_refund_requests} and the {@code support} macros.
 */
@Repository
@RequiredArgsConstructor
class SupportDeskJdbc implements SupportDeskStore {

    private static final TypeReference<Map<String, @Nullable Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};

    private static final String TICKET = """
            select t.id, t.number, t.requester_type, t.requester_id, t.merchant_id, t.subject, t.priority, t.urgent,
                   t.state, t.agent_id, t.agent_name, t.created_at, t.sla_due_at, t.lang, t.escalated_at, t.ref_label,
                   t.context::text as context
              from messaging.tickets t
            """;

    private static final String REQUEST = """
            select id, ticket_id, amount_cents, note, requested_by, requested_at, state, decided_by, decided_at,
                   decision_note
              from messaging.support_refund_requests
            """;

    private final JdbcClient jdbc;

    @Override
    public List<TicketRow> open(MerchantScope scope, int limit) {
        return jdbc.sql(TICKET + """
                         where t.state <> 'resolved'
                           and (:everyone or t.merchant_id = any(:merchants))
                         order by t.sla_due_at nulls last, t.created_at, t.number
                         limit :limit
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("limit", limit)
                .query((rs, _) -> ticket(rs))
                .list();
    }

    @Override
    public Optional<TicketRow> find(String ticketId) {
        return jdbc.sql(TICKET + " where t.id = :id")
                .param("id", ticketId)
                .query((rs, _) -> ticket(rs))
                .optional();
    }

    @Override
    public Optional<TicketRow> lock(String ticketId) {
        return jdbc.sql(TICKET + " where t.id = :id for update")
                .param("id", ticketId)
                .query((rs, _) -> ticket(rs))
                .optional();
    }

    @Override
    public Figures figures(MerchantScope scope, Instant since) {
        return jdbc.sql("""
                        select percentile_cont(0.5) within group (
                                 order by extract(epoch from (t.first_replied_at - t.created_at)) / 60.0)
                                 filter (where t.first_replied_at is not null and t.created_at >= :since) as median_reply,
                               (count(*) filter (where t.state = 'resolved' and t.resolved_at >= :since
                                                   and t.escalated_at is null))::float8
                                 / nullif(count(*) filter (where t.state = 'resolved' and t.resolved_at >= :since), 0)
                                 as unescalated,
                               avg(t.csat) filter (where t.csat is not null and t.created_at >= :since)::float8 as csat
                          from messaging.tickets t
                         where (:everyone or t.merchant_id = any(:merchants))
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(since))
                .query((rs, _) ->
                        new Figures(number(rs, "median_reply"), number(rs, "unescalated"), number(rs, "csat")))
                .single();
    }

    @Override
    public String thread(TicketRow ticket, Instant at) {
        var existing = jdbc.sql("""
                        select id from messaging.threads
                         where ref_type = 'ticket' and ref_id = :t and kind = 'case'
                         order by created_at limit 1
                        """)
                .param("t", ticket.id())
                .query((rs, _) -> rs.getString("id"))
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = Ids.next();
        jdbc.sql("""
                        insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_name,
                                                       subject, created_at, last_message_at)
                        values (:id, :m, 'case', 'ticket', :t, :code, 'Northline support', :subject, :at, :at)
                        """)
                .param("id", id)
                .param("m", ticket.merchantId())
                .param("t", ticket.id())
                .param("code", SupportCase.code(ticket.number()))
                .param("subject", ticket.subject())
                .param("at", ts(at))
                .update();
        return id;
    }

    @Override
    public List<Note> notes(String ticketId) {
        return jdbc.sql("""
                        select m.sender_role, m.sender_name, m.body, m.at
                          from messaging.threads h join messaging.messages m on m.thread_id = h.id
                         where h.ref_type = 'ticket' and h.ref_id = :t and h.kind = 'case'
                         order by m.at, m.id
                        """)
                .param("t", ticketId)
                .query((rs, _) -> new Note(
                        Objects.requireNonNullElse(rs.getString("sender_role"), "merchant"),
                        rs.getString("sender_name"),
                        Objects.requireNonNullElse(rs.getString("body"), ""),
                        requiredInstant(rs, "at")))
                .list();
    }

    @Override
    public void message(
            String threadId, String senderRole, String senderId, String senderName, String body, Instant at) {
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body,
                                                        attachments, at, flagged)
                        values (:id, :t, :sender, :role, :name, :body, '{}', :at, false)
                        """)
                .param("id", Ids.next())
                .param("t", threadId)
                .param("sender", senderId)
                .param("role", senderRole)
                .param("name", senderName)
                .param("body", body)
                .param("at", ts(at))
                .update();
        jdbc.sql("update messaging.threads set last_message_at = :at where id = :t")
                .param("t", threadId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void replied(String ticketId, String state, Instant at) {
        jdbc.sql("""
                        update messaging.tickets
                           set state = :state,
                               first_replied_at = coalesce(first_replied_at, :at),
                               resolved_at = case when :state = 'resolved' then :at else resolved_at end,
                               updated_at = :at
                         where id = :id
                        """)
                .param("id", ticketId)
                .param("state", state)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void assign(String ticketId, String agentId, String agentName, Instant at) {
        jdbc.sql("""
                        update messaging.tickets
                           set agent_id = :agent, agent_name = :name, updated_at = :at,
                               state = case when state = 'new' then 'in_progress' else state end
                         where id = :id
                        """)
                .param("id", ticketId)
                .param("agent", agentId)
                .param("name", agentName)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void escalate(String ticketId, String staffId, Instant at) {
        jdbc.sql("""
                        update messaging.tickets set escalated_at = :at, escalated_by = :by, updated_at = :at
                         where id = :id
                        """)
                .param("id", ticketId)
                .param("by", staffId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void insertRefundRequest(String ticketId, RefundRequest r) {
        jdbc.sql("""
                        insert into messaging.support_refund_requests (id, ticket_id, amount_cents, note, requested_by,
                                                                       requested_at, state)
                        values (:id, :t, :amount, :note, :by, :at, :state)
                        """)
                .param("id", r.id())
                .param("t", ticketId)
                .param("amount", r.amountCents())
                .param("note", r.note())
                .param("by", r.requestedBy())
                .param("at", ts(r.requestedAt()))
                .param("state", r.state())
                .update();
    }

    @Override
    public List<RefundRequest> refundRequests(String ticketId) {
        return jdbc.sql(REQUEST + " where ticket_id = :t order by requested_at desc, id")
                .param("t", ticketId)
                .query((rs, _) -> request(rs))
                .list();
    }

    @Override
    public Optional<Map.Entry<String, RefundRequest>> refundRequest(String requestId) {
        return jdbc.sql(REQUEST + " where id = :id")
                .param("id", requestId)
                .query((rs, _) -> Map.entry(rs.getString("ticket_id"), request(rs)))
                .optional();
    }

    @Override
    public List<Map.Entry<String, RefundRequest>> pendingRefundRequests(MerchantScope scope, int limit) {
        return jdbc.sql("""
                        select r.id, r.ticket_id, r.amount_cents, r.note, r.requested_by, r.requested_at, r.state,
                               r.decided_by, r.decided_at, r.decision_note
                          from messaging.support_refund_requests r
                          join messaging.tickets t on t.id = r.ticket_id
                         where r.state = 'pending' and (:everyone or t.merchant_id = any(:merchants))
                         order by r.requested_at, r.id
                         limit :limit
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("limit", limit)
                .query((rs, _) -> Map.entry(rs.getString("ticket_id"), request(rs)))
                .list();
    }

    @Override
    public boolean decideRefundRequest(
            String requestId, String state, String staffId, @Nullable String note, Instant at) {
        return jdbc.sql("""
                                update messaging.support_refund_requests
                                   set state = :state, decided_by = :by, decided_at = :at, decision_note = :note
                                 where id = :id and state = 'pending'
                                """)
                        .param("id", requestId)
                        .param("state", state)
                        .param("by", staffId)
                        .param("at", ts(at))
                        .param("note", note)
                        .update()
                > 0;
    }

    @Override
    public List<Macro> macros() {
        return jdbc.sql("""
                        select id, key, title_i18n::text as title, body_i18n::text as body, updated_at
                          from messaging.macros where topic = 'support' order by position, key
                        """).query((rs, _) -> macro(rs)).list();
    }

    @Override
    public Optional<Macro> macro(String id) {
        return jdbc.sql("""
                        select id, key, title_i18n::text as title, body_i18n::text as body, updated_at
                          from messaging.macros where topic = 'support' and id = :id
                        """).param("id", id).query((rs, _) -> macro(rs)).optional();
    }

    @Override
    public boolean macroKeyTaken(String key, @Nullable String exceptId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from messaging.macros
                                        where key = :key and (cast(:except as text) is null or id <> :except))
                        """)
                .param("key", key)
                .param("except", exceptId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public void saveMacro(Macro macro, String staffId, Instant at) {
        jdbc.sql("""
                        insert into messaging.macros (id, key, topic, position, title_i18n, body_i18n, updated_by,
                                                      updated_at)
                        values (:id, :key, 'support',
                                (select coalesce(max(position), 0) + 1 from messaging.macros where topic = 'support'),
                                cast(:title as jsonb), cast(:body as jsonb), :by, :at)
                        on conflict (id) do update
                           set key = excluded.key, title_i18n = excluded.title_i18n, body_i18n = excluded.body_i18n,
                               updated_by = excluded.updated_by, updated_at = excluded.updated_at
                        """)
                .param("id", macro.id())
                .param("key", macro.key())
                .param("title", json(macro.title()))
                .param("body", json(macro.body()))
                .param("by", staffId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void deleteMacro(String id) {
        jdbc.sql("delete from messaging.macros where id = :id and topic = 'support'")
                .param("id", id)
                .update();
    }

    private static TicketRow ticket(ResultSet rs) throws SQLException {
        var context = rs.getString("context");
        return new TicketRow(
                rs.getString("id"),
                rs.getInt("number"),
                Objects.requireNonNullElse(rs.getString("requester_type"), "merchant"),
                rs.getString("requester_id"),
                rs.getString("merchant_id"),
                Objects.requireNonNullElse(rs.getString("subject"), ""),
                rs.getBoolean("urgent") ? "urgent" : Objects.requireNonNullElse(rs.getString("priority"), "normal"),
                Objects.requireNonNullElse(rs.getString("state"), "new"),
                rs.getString("agent_id"),
                rs.getString("agent_name"),
                requiredInstant(rs, "created_at"),
                instant(rs, "sla_due_at"),
                rs.getString("lang"),
                instant(rs, "escalated_at"),
                rs.getString("ref_label"),
                context == null ? Map.of() : withoutNulls(JSON.readValue(context, OBJECT)));
    }

    /** The case context as stored, minus keys whose value is null (immutable maps refuse them). */
    private static Map<String, Object> withoutNulls(Map<String, @Nullable Object> map) {
        var out = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static RefundRequest request(ResultSet rs) throws SQLException {
        return new RefundRequest(
                rs.getString("id"),
                rs.getLong("amount_cents"),
                rs.getString("note"),
                rs.getString("requested_by"),
                requiredInstant(rs, "requested_at"),
                rs.getString("state"),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("decision_note"),
                null);
    }

    private static Macro macro(ResultSet rs) throws SQLException {
        var title = rs.getString("title");
        var body = rs.getString("body");
        return new Macro(
                rs.getString("id"),
                rs.getString("key"),
                title == null ? Map.of() : JSON.readValue(title, TEXT),
                body == null ? Map.of() : JSON.readValue(body, TEXT),
                instant(rs, "updated_at"));
    }

    private static @Nullable Double number(ResultSet rs, String column) throws SQLException {
        var value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
