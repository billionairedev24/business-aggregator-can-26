package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.instant;
import static ca.northline.messaging.persistence.MessagingSql.json;
import static ca.northline.messaging.persistence.MessagingSql.requiredInstant;
import static ca.northline.messaging.persistence.MessagingSql.ts;

import ca.northline.messaging.application.HelpCaseStore;
import ca.northline.messaging.application.ManageHelpCases.CaseSummary;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.messaging.domain.TicketPriority;
import ca.northline.messaging.domain.TicketState;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Helpdesk cases of a business over {@code messaging.tickets}. "Last 5 events" for the account context are read from
 * the Modulith event registry ({@code events.event_publication} and its archive — shared infrastructure, not a module).
 */
@Repository
@RequiredArgsConstructor
class HelpCaseAdapter implements HelpCaseStore {

    private static final String SUMMARY = """
            select t.id, t.number, t.subject, t.topic, t.state, t.priority, t.urgent, t.channel, t.agent_name,
                   t.sla_due_at, t.resolved_at, t.resolution_note, t.ref_type, t.ref_id, t.ref_label, t.created_at,
                   (select max(m.at) from messaging.threads th join messaging.messages m on m.thread_id = th.id
                     where th.ref_type = 'ticket' and th.ref_id = t.id and m.sender_role = 'agent') as agent_reply_at
              from messaging.tickets t
             where t.merchant_id = :m and t.requester_type = 'merchant'
            """;

    private final JdbcClient jdbc;

    @Override
    public int insert(NewCase c) {
        return jdbc.sql("""
                        insert into messaging.tickets (id, requester_type, requester_id, merchant_id, opened_by, topic,
                                                       subject, priority, state, channel, urgent, sla_due_at, ref_type,
                                                       ref_id, ref_label, lang, context, created_at, updated_at)
                        values (:id, 'merchant', :m, :m, :by, :topic, :subject, :priority, 'new', :channel, :urgent,
                                :due, :refType, :refId, :refLabel, :lang, cast(:context as jsonb), :at, :at)
                        returning number
                        """)
                .param("id", c.id())
                .param("m", c.merchantId())
                .param("by", c.openedBy())
                .param("topic", c.topic())
                .param("subject", c.subject())
                .param("priority", c.priority().code())
                .param("channel", c.channel())
                .param("urgent", c.urgent())
                .param("due", ts(c.slaDueAt()))
                .param("refType", c.refType())
                .param("refId", c.refId())
                .param("refLabel", c.refLabel())
                .param("lang", c.lang())
                .param("context", json(c.context()))
                .param("at", ts(c.createdAt()))
                .query(Integer.class)
                .single();
    }

    @Override
    public List<CaseSummary> list(String merchantId) {
        return jdbc.sql(SUMMARY + " order by (t.state <> 'resolved') desc, t.created_at desc, t.number desc")
                .param("m", merchantId)
                .query((rs, _) -> summary(rs))
                .list();
    }

    @Override
    public Optional<CaseSummary> find(String merchantId, String caseId) {
        return jdbc.sql(SUMMARY + " and t.id = :id")
                .param("m", merchantId)
                .param("id", caseId)
                .query((rs, _) -> summary(rs))
                .optional();
    }

    @Override
    public Optional<SupportCase> state(String merchantId, String caseId) {
        return jdbc.sql("""
                        select id, number, state, priority, sla_due_at from messaging.tickets
                         where merchant_id = :m and requester_type = 'merchant' and id = :id
                        """)
                .param("m", merchantId)
                .param("id", caseId)
                .query((rs, _) -> new SupportCase(
                        rs.getString("id"),
                        rs.getInt("number"),
                        CodedEnum.fromCode(TicketState.class, rs.getString("state")),
                        Objects.requireNonNull(CodedEnums.fromCode(rs.getString("priority"), TicketPriority.class)),
                        instant(rs, "sla_due_at")))
                .optional();
    }

    @Override
    public void update(SupportCase progress, Instant at) {
        jdbc.sql("""
                        update messaging.tickets set state = :state, sla_due_at = :due, updated_at = :at where id = :id
                        """)
                .param("id", progress.id())
                .param("state", progress.state().code())
                .param("due", ts(progress.slaDueAt()))
                .param("at", ts(at))
                .update();
    }

    @Override
    public int openCount(String merchantId) {
        return jdbc.sql("""
                        select count(*) from messaging.tickets
                         where merchant_id = :m and requester_type = 'merchant' and state <> 'resolved'
                        """).param("m", merchantId).query(Integer.class).single();
    }

    @Override
    public List<RecentEvent> recentEvents(String merchantId, int limit) {
        return jdbc.sql("""
                        select event_type, publication_date from (
                          select distinct on (serialized_event) event_type, serialized_event, publication_date
                            from (select event_type, serialized_event, publication_date from events.event_publication
                                  union all
                                  select event_type, serialized_event, publication_date
                                    from events.event_publication_archive) e
                           where position(:m in serialized_event) > 0
                           order by serialized_event, publication_date
                        ) d
                         order by publication_date desc
                         limit :limit
                        """)
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> {
                    var type = rs.getString("event_type");
                    return new RecentEvent(
                            type.substring(type.lastIndexOf('.') + 1), requiredInstant(rs, "publication_date"));
                })
                .list();
    }

    private static CaseSummary summary(ResultSet rs) throws SQLException {
        return new CaseSummary(
                rs.getString("id"),
                SupportCase.code(rs.getInt("number")),
                Objects.requireNonNullElse(rs.getString("subject"), ""),
                Objects.requireNonNullElse(rs.getString("topic"), "other"),
                CodedEnum.fromCode(TicketState.class, rs.getString("state")),
                Objects.requireNonNullElse(
                        CodedEnums.fromCode(rs.getString("priority"), TicketPriority.class), TicketPriority.NORMAL),
                rs.getBoolean("urgent"),
                rs.getString("channel"),
                rs.getString("agent_name"),
                instant(rs, "agent_reply_at"),
                instant(rs, "sla_due_at"),
                instant(rs, "resolved_at"),
                rs.getString("resolution_note"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("ref_label"),
                requiredInstant(rs, "created_at"));
    }
}
