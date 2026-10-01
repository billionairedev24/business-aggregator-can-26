package ca.northline.messaging.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.messaging.api.CustomerCaseDesk.Attachment;
import ca.northline.messaging.api.CustomerCaseDesk.CaseThread;
import ca.northline.messaging.api.CustomerCaseDesk.Note;
import ca.northline.messaging.application.CustomerCaseStore;
import ca.northline.shared.Ids;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CustomerCaseStore}: customer tickets ({@code requester_type = 'customer'}, no business — so they never show
 * in a Studio's Help), their {@code case} thread without a business, and {@code messaging.customer_uploads} (V164).
 */
@Repository
@RequiredArgsConstructor
class CustomerCaseJdbc implements CustomerCaseStore {

    private final JdbcClient jdbc;

    @Override
    public int insert(NewTicket t) {
        return jdbc.sql("""
                        insert into messaging.tickets (id, requester_type, requester_id, opened_by, topic, subject, priority,
                               urgent, state, sla_due_at, ref_type, ref_id, ref_label, lang, context, created_at, updated_at)
                        values (:id, 'customer', :c, :c, :topic, :subject, :priority, :urgent, 'new', :due, :refType,
                                :refId, :refLabel, :lang, cast(:context as jsonb), :at, :at)
                        returning number
                        """)
                .param("id", t.id())
                .param("c", t.customerId())
                .param("topic", t.topic())
                .param("subject", t.subject())
                .param("priority", t.priority().code())
                .param("urgent", t.urgent())
                .param("due", ts(t.slaDueAt()))
                .param("refType", t.refType())
                .param("refId", t.refId())
                .param("refLabel", t.refLabel())
                .param("lang", t.lang())
                .param("context", t.contextJson())
                .param("at", ts(t.createdAt()))
                .query(Integer.class)
                .single();
    }

    @Override
    public void thread(String threadId, String ticketId, String code, String subject, Instant at) {
        jdbc.sql("""
                        insert into messaging.threads (id, kind, ref_type, ref_id, ref_code, counterpart_name, subject,
                                                       created_at, last_message_at)
                        values (:id, 'case', 'ticket', :t, :code, 'Northline support', :subject, :at, :at)
                        """)
                .param("id", threadId)
                .param("t", ticketId)
                .param("code", code)
                .param("subject", subject)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void message(String threadId, String senderRole, String senderId, String body, List<String> files, Instant at) {
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, body, attachments, at, flagged)
                        values (:id, :t, :sender, :role, :body, :files, :at, false)
                        """)
                .param("id", Ids.next())
                .param("t", threadId)
                .param("sender", senderId)
                .param("role", senderRole)
                .param("body", body)
                .param("files", files.toArray(String[]::new))
                .param("at", ts(at))
                .update();
        jdbc.sql("update messaging.threads set last_message_at = :at where id = :t")
                .param("at", ts(at))
                .param("t", threadId)
                .update();
    }

    @Override
    public Optional<CaseThread> forRefund(String customerId, String refundId) {
        return jdbc.sql("""
                        select id from messaging.tickets
                         where requester_type = 'customer' and requester_id = :c
                           and (context -> 'refunds') @> jsonb_build_array(cast(:refund as text))
                         order by created_at desc limit 1
                        """)
                .param("c", customerId)
                .param("refund", refundId)
                .query(String.class)
                .optional()
                .flatMap(id -> find(customerId, id));
    }

    @Override
    public Optional<CaseThread> find(String customerId, String ticketId) {
        var head = jdbc.sql("""
                        select t.id, t.number, coalesce(t.state, 'new') as state, h.id as thread
                          from messaging.tickets t
                          left join messaging.threads h on h.ref_type = 'ticket' and h.ref_id = t.id and h.kind = 'case'
                         where t.id = :id and t.requester_type = 'customer' and t.requester_id = :c
                        """)
                .param("id", ticketId)
                .param("c", customerId)
                .query((rs, _) -> new Head(
                        rs.getString("id"), "HD-" + rs.getInt("number"), rs.getString("state"), rs.getString("thread")))
                .optional();
        return head.map(h -> new CaseThread(
                h.id(), h.code(), h.state(), h.thread() == null ? List.of() : notes(h.thread(), customerId)));
    }

    private record Head(String id, String code, String state, @Nullable String thread) {}

    private List<Note> notes(String threadId, String customerId) {
        record Row(Instant at, String by, String body, List<String> files) {}
        var rows = jdbc.sql("""
                        select at, sender_id, sender_role, coalesce(body, '') as body, attachments
                          from messaging.messages where thread_id = :t order by at, id
                        """)
                .param("t", threadId)
                .query((rs, _) -> new Row(
                        requiredInstant(rs, "at"),
                        customerId.equals(rs.getString("sender_id")) ? "you" : "northline",
                        rs.getString("body"),
                        strings(rs.getArray("attachments"))))
                .list();
        return rows.stream().map(r -> new Note(r.at(), r.by(), r.body(), attachments(r.files()))).toList();
    }

    @Override
    public Optional<String> threadOf(String customerId, String ticketId) {
        return jdbc.sql("""
                        select h.id from messaging.tickets t
                          join messaging.threads h on h.ref_type = 'ticket' and h.ref_id = t.id and h.kind = 'case'
                         where t.id = :id and t.requester_type = 'customer' and t.requester_id = :c
                        """)
                .param("id", ticketId)
                .param("c", customerId)
                .query(String.class)
                .optional();
    }

    @Override
    public void touched(String ticketId, Instant at) {
        jdbc.sql("""
                        update messaging.tickets set updated_at = :at,
                               state = case when state = 'waiting' then 'in_progress' else state end
                         where id = :id
                        """)
                .param("at", ts(at))
                .param("id", ticketId)
                .update();
    }

    @Override
    public @Nullable String stateOf(String ticketId) {
        return jdbc.sql("select state from messaging.tickets where id = :id")
                .param("id", ticketId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Override
    public void insertUpload(StoredUpload u) {
        jdbc.sql("""
                        insert into messaging.customer_uploads (id, customer_id, storage_key, file_name, content_type,
                               byte_size, created_at)
                        values (:id, :c, :key, :name, :type, :size, :at)
                        """)
                .param("id", u.id())
                .param("c", u.customerId())
                .param("key", u.storageKey())
                .param("name", u.fileName())
                .param("type", u.contentType())
                .param("size", u.byteSize())
                .param("at", ts(u.at()))
                .update();
    }

    @Override
    public Optional<StoredUpload> upload(String customerId, String id) {
        return jdbc.sql("""
                        select id, customer_id, storage_key, file_name, content_type, byte_size, created_at
                          from messaging.customer_uploads where id = :id and customer_id = :c
                        """)
                .param("id", id)
                .param("c", customerId)
                .query((rs, _) -> new StoredUpload(
                        rs.getString("id"),
                        rs.getString("customer_id"),
                        rs.getString("storage_key"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("byte_size"),
                        requiredInstant(rs, "created_at")))
                .optional();
    }

    @Override
    public List<String> ownUploads(String customerId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select id from messaging.customer_uploads where customer_id = :c and id in (:ids)")
                .param("c", customerId)
                .param("ids", List.copyOf(ids))
                .query(String.class)
                .list();
    }

    @Override
    public List<Attachment> attachments(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id, file_name, content_type, byte_size from messaging.customer_uploads where id in (:ids)
                        union all
                        select id, file_name, content_type, byte_size from messaging.attachments where id in (:ids)
                        """)
                .param("ids", List.copyOf(ids))
                .query((rs, _) -> new Attachment(
                        rs.getString("id"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("byte_size")))
                .list();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
